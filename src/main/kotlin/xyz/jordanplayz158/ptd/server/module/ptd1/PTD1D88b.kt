package xyz.jordanplayz158.ptd.server.module.ptd1

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import org.jetbrains.exposed.dao.with
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.jordanplayz158.ptd.server.common.*
import xyz.jordanplayz158.ptd.server.module.ptd1.controller.PTD1SWFController
import xyz.jordanplayz158.ptd.server.module.ptd1.orm.*

/**
 * Protocol translator for the d88b/ptd.onl PTD1 v3.6.6 client build, which speaks the
 * ptd.ooo "save.php" dialect rather than the original newPoke8.php one.
 *
 * == LOAD (server -> client, parsed by profile_user.method_279 + screen_Profile.init) ==
 * Top-level urlencoded keys per slot N (1..3), all lowercase:
 *   nickname<N>, avatar<N>, advanced<N>, advanced_a<N>, classic<N>, challenge<N>,
 *   badges<N>, money<N>, version<N>, PC<N> (pokemon count)
 * Plus: p<N>PN<i> (poke i nickname), p<N>extra (pokemon blob), p<N>extra2 (items blob),
 *   p<N>extra3 (achievements blob), p<N>extra4 (extra-info blob), and a single "pokedex"
 *   string (151 chars, digit per species: 1=reg 2=shiny 3=shadow 4=reg+shiny 5=reg+shadow
 *   6=shiny+shadow 7=all).
 *
 * == Number/string codec (profile_user.convertIntToString) ==
 * Per-decimal-digit cipher via letterList = m y w c q a p r e o  (index = digit 0..9).
 * Fields carry length prefixes:
 *   single-level value: cipher(numDigits(v)) + cipher(v)          (small values)
 *   double-level value: cipher(numDigits(numDigits(v))) + cipher(numDigits(v)) + cipher(v)
 *   string:             cipher(len(s)) + rawChars(s)
 * A blob = cipher(getLength(inner.len)) + single(count) + body, where getLength is the
 * self-including length used by the client (profile_user.get_Length).
 *
 * LOAD per-pokemon body order (method_279): num(s) exp(d) level(s) m1(s) m2(s) m3(s) m4(s)
 *   mSel(s) targetType(s) myID(d) pos(s) shiny(s) tag(str).
 *
 * == SAVE (client -> server, produced by profile_user.save_Poke) ==
 * saveString urlencoded with lowercase keys: currentSave, [newGame=yes], nickname, version,
 *   num (slot 1..3), badges, money, advanced, advanced_a, classic, challenge, myTID, myVID,
 *   avatar, extra (pokemon change-set), [releasePoke=a|b|...], extra2 (items), extra4, pokedex.
 * Pokemon change-set per entry: single(reasonCount) + myID(d) + reasonCount records, each
 *   record = single(reasonCode) + payload:
 *     1 captured: num(s) exp(d) level(s) m1(s) m2(s) m3(s) m4(s) mSel(s) targetType(s) pos(s) extra(s) tag(str)
 *     10 trade:   num(s) exp(d) level(s) m1(s) m2(s) m3(s) m4(s) mSel(s) targetType(s) pos(s)
 *     2 level: level(s)   3 exp: exp(d)   4 moves: m1(s) m2(s) m3(s) m4(s)
 *     5 mSel: mSel(s)     6 evolve: num(s)   7 target: targetType(s)   8 pos: pos(s)   9 tag: tag(str)
 */
object PTD1D88b {
    private const val LETTERS = "mywcqapreo" // index = digit value

    private fun cipher(n: Int): String = n.toString().map { LETTERS[it.digitToInt()] }.joinToString("")

    fun single(v: Int): String {
        val s = v.toString()
        return cipher(s.length) + cipher(v)
    }

    fun double(v: Int): String {
        val s = v.toString()
        return cipher(s.length.toString().length) + cipher(s.length) + cipher(v)
    }

    fun str(s: String): String = cipher(s.length) + s

    /** Mirrors profile_user.get_Length: self-including length, returns "<numDigits><value>". */
    private fun getLength(len: Int): Int {
        var numDigits = len.toString().length
        while (true) {
            val v = len + 1 + numDigits
            val vStr = v.toString()
            if (vStr.length == numDigits) return "$numDigits$vStr".toInt()
            numDigits = vStr.length
        }
    }

    fun blob(count: Int, body: String): String {
        val inner = single(count) + body
        return cipher(getLength(inner.length)) + inner
    }

    /** Sequential reader for ciphered blobs (client -> server). */
    class Reader(private val s: String) {
        var i = 0
        private fun decipher(c: Char): Int {
            val d = LETTERS.indexOf(c)
            require(d >= 0) { "bad cipher char '$c' at $i" }
            return d
        }
        private fun decN(count: Int): Int {
            val sb = StringBuilder()
            repeat(count) { sb.append(decipher(s[i++])) }
            return sb.toString().toInt()
        }
        fun single(): Int { val len = decipher(s[i++]); return decN(len) }
        fun double(): Int { val lenLen = decipher(s[i++]); val len = decN(lenLen); return decN(len) }
        fun rawStr(): String { val len = decipher(s[i++]); val v = s.substring(i, i + len); i += len; return v }
        fun hasMore(): Boolean = i < s.length
    }

    private val EMPTY_BLOB = blob(0, "")

    // mask (reg=1, shiny=2, shadow=4) -> client pokedex digit
    private val DEX_DIGIT = charArrayOf('0', '1', '2', '4', '3', '5', '6', '7')

    private fun parseBool(s: String?): Boolean = s == "1" || s.equals("true", ignoreCase = true)

    /**
     * Map the client's saveInfo.extra capture code to stored rarity (0 normal, 1 shiny, 2 shadow).
     * Mirrors PTD1SWFController.saveAccount. MissingNo: 181 normal -> 0, 182 shiny -> 1.
     * The raw extra code must NOT be stored as rarity: on reload it becomes the client's `shiny`
     * field and fails profile_poke.check_Up -> is_Legal() flags the save as a hack.
     */
    private fun extraToRarity(extra: Int): Short = when (extra) {
        1, 2, 3, 4, 5, 6, 151, 153, 168, 182, 854 -> 1 // shiny
        180, 555, 855 -> 2 // shadow
        else -> 0 // normal (incl 0, 152, 154, 181, 201, 857)
    }

    private fun parseVersion(s: String?): SaveVersionEnum =
        s?.toByteOrNull()?.let { id -> SaveVersionEnum.entries.firstOrNull { it.id == id } } ?: SaveVersionEnum.RED

    fun loadAccount(user: PTD1User): List<Pair<String, String>> {
        return transaction {
            val response = ArrayList<Pair<String, String>>()
            response.addAll(ReasonsEnum.SUCCESS_LOGGED_IN.fullReason())

            val currentSave = PTD1SWFController.currentSave()
            val trainerId = (333..99999).random()
            response.add("CurrentSave" to currentSave)
            response.add("TrainerID" to trainerId.toString())
            response.add("ProfileID" to PTD1Keygen.generateProfileId(currentSave, trainerId))

            user.saves.forEach { save ->
                val n = save.number + 1
                response.add("nickname$n" to save.nickname)
                response.add("avatar$n" to save.avatar)
                response.add("advanced$n" to save.levelsStarted.toString())
                response.add("advanced_a$n" to save.levelsCompleted.toString())
                response.add("classic$n" to save.hasFlash.toInt().toString())
                response.add("challenge$n" to save.challenge.toString())
                response.add("badges$n" to save.badges.toString())
                response.add("money$n" to save.money.toString())
                response.add("version$n" to save.version.id.toString())

                val pokes = save.pokemon.toList()
                response.add("PC$n" to pokes.size.toString())

                val body = StringBuilder()
                pokes.forEachIndexed { idx, p ->
                    response.add("p${n}PN${idx + 1}" to p.nickname)
                    body.append(single(p.number.toInt()))
                        .append(double(p.experience))
                        .append(single(p.level.toInt()))
                        .append(single(p.move1.toInt()))
                        .append(single(p.move2.toInt()))
                        .append(single(p.move3.toInt()))
                        .append(single(p.move4.toInt()))
                        .append(single(p.moveSelected.toInt()))
                        .append(single(p.targetType.toInt()))
                        .append(double(p.swfId))
                        .append(single(p.position))
                        .append(single(p.rarity.toInt()))
                        .append(str(p.tag))
                }
                response.add("p${n}extra" to blob(pokes.size, body.toString()))

                val items = save.items.toList()
                val itemBody = StringBuilder()
                items.forEach { item ->
                    itemBody.append(single(item.item.id.toInt())).append(single(item.quantity.toInt()))
                }
                response.add("p${n}extra2" to blob(items.size, itemBody.toString()))

                response.add("p${n}extra3" to EMPTY_BLOB) // achievements travel via achieve.php
                response.add("p${n}extra4" to EMPTY_BLOB) // extra-info: no storage yet
            }

            val dex = user.dex.padEnd(151, '0')
            val shiny = user.shinyDex.padEnd(151, '0')
            val shadow = user.shadowDex.padEnd(151, '0')
            val pokedex = StringBuilder(151)
            for (i in 0 until 151) {
                var mask = 0
                if (dex[i] == '1') mask = mask or 1
                if (shiny[i] == '1') mask = mask or 2
                if (shadow[i] == '1') mask = mask or 4
                pokedex.append(DEX_DIGIT[mask])
            }
            response.add("pokedex" to pokedex.toString())

            return@transaction response
        }
    }

    fun saveAccount(user: PTD1User, parameters: Parameters): List<Pair<String, String>> {
        val saveString = parameters["saveString"]
            ?: return ReasonsEnum.FAILURE_NOT_ALL_PARAMETERS_SUPPLIED.fullReason()

        val save = saveString.parseUrlEncodedParameters(Charsets.UTF_8, 0)

        return transaction {
            val slotNumber = save["num"]!!.toInt() - 1
            var userSave = user.saves.first { s -> s.number.toInt() == slotNumber }

            if (save["newGame"] == "yes") {
                userSave.pokemon.forEach { it.delete() }
                userSave.items.forEach { it.delete() }
                userSave.delete()
                userSave = PTD1Save.new {
                    this.user = user.id
                    this.number = slotNumber.toByte()
                    this.version = parseVersion(save["version"])
                }
            }

            userSave.nickname = save["nickname"]!!
            userSave.version = parseVersion(save["version"])
            userSave.badges = save["badges"]!!.toByte()
            userSave.money = save["money"]!!.toInt()
            userSave.levelsStarted = save["advanced"]!!.toByte()
            userSave.levelsCompleted = save["advanced_a"]!!.toByte()
            userSave.hasFlash = parseBool(save["classic"])
            userSave.challenge = save["challenge"]!!.toByte()
            userSave.avatar = save["avatar"]!!
            val saveId = userSave.id

            val released: Set<Int> = save["releasePoke"]
                ?.split('|')?.mapNotNull { it.toIntOrNull() }?.filter { it != 0 }?.toSet() ?: emptySet()

            // Newly-created pokemon report their assigned swfId back as newPokePos_<position>
            // so the client (profile_user.method_192) can replace its temporary myID=0.
            val newPokePos = LinkedHashMap<Int, Int>()
            var maxSwfId = userSave.pokemon.maxOfOrNull { it.swfId } ?: 0

            // Pokemon change-set
            save["extra"]?.let { extra ->
                val r = Reader(extra)
                r.single() // total length (client self-describing; ignore value)
                val count = r.single()
                var processed = 0
                while (processed < count && r.hasMore()) {
                    processed++
                    val reasonCount = r.single()
                    val clientMyId = r.double()

                    if (clientMyId != 0 && clientMyId in released) {
                        userSave.pokemon.find { it.swfId == clientMyId }?.delete()
                        repeat(reasonCount) { consumeReason(r) }
                        continue
                    }

                    // myId 0 (or unknown) means a freshly captured pokemon: assign a new swfId.
                    var poke = if (clientMyId != 0) userSave.pokemon.find { it.swfId == clientMyId } else null
                    val isNew = poke == null
                    if (isNew) {
                        maxSwfId++
                        val assignedId = maxSwfId
                        poke = PTD1Pokemon.new {
                            this.save = saveId
                            this.swfId = assignedId
                            this.number = 0.toShort() // overwritten by capture/evolve records below
                            this.nickname = "" // d88b save protocol does not transmit nicknames
                        }
                    }

                    repeat(reasonCount) { applyReason(r, poke!!) }

                    if (isNew) newPokePos[poke!!.position] = poke.swfId
                }
            }

            // Items in extra2: header + (itemId, quantity) pairs. Replace wholesale.
            save["extra2"]?.let { extra2 ->
                userSave.items.forEach { it.delete() }
                val r = Reader(extra2)
                r.single() // total length
                val count = r.single()
                repeat(count) {
                    val itemId = r.single()
                    val quantity = r.single()
                    PTD1SaveItem.new {
                        this.save = saveId
                        this.item = PTD1ItemsEnum.get(itemId.toShort())
                        this.quantity = quantity.toByte()
                    }
                }
            }

            // extra4 (extra-info) accepted and dropped — no storage yet.

            save["pokedex"]?.let { pokedex ->
                val reg = StringBuilder(); val shiny = StringBuilder(); val shadow = StringBuilder()
                pokedex.forEach { c ->
                    reg.append(if (c in "1457") '1' else '0')
                    shiny.append(if (c in "2467") '1' else '0')
                    shadow.append(if (c in "3567") '1' else '0')
                }
                user.dex = reg.toString().trimEnd('0')
                user.shinyDex = shiny.toString().trimEnd('0')
                user.shadowDex = shadow.toString().trimEnd('0')
            }

            val response = ArrayList<Pair<String, String>>()
            response.add("Result" to "Success")
            response.add("Reason" to "saved")
            response.add("newSave" to "0")
            newPokePos.forEach { (position, swfId) ->
                response.add("newPokePos_$position" to swfId.toString())
            }
            return@transaction response
        }
    }

    /** Read a change record's payload and apply to the pokemon. */
    private fun applyReason(r: Reader, poke: PTD1Pokemon) {
        when (r.single()) {
            1 -> { // captured (full)
                poke.number = r.single().toShort()
                poke.experience = r.double()
                poke.level = r.single().toShort()
                poke.move1 = r.single().toShort()
                poke.move2 = r.single().toShort()
                poke.move3 = r.single().toShort()
                poke.move4 = r.single().toShort()
                poke.moveSelected = r.single().toShort()
                poke.targetType = r.single().toShort()
                poke.position = r.single()
                poke.rarity = extraToRarity(r.single()) // saveInfo.extra code -> rarity 0/1/2
                poke.tag = r.rawStr()
            }
            10 -> { // trade
                poke.number = r.single().toShort()
                poke.experience = r.double()
                poke.level = r.single().toShort()
                poke.move1 = r.single().toShort()
                poke.move2 = r.single().toShort()
                poke.move3 = r.single().toShort()
                poke.move4 = r.single().toShort()
                poke.moveSelected = r.single().toShort()
                poke.targetType = r.single().toShort()
                poke.position = r.single()
            }
            2 -> poke.level = r.single().toShort()
            3 -> poke.experience = r.double()
            4 -> {
                poke.move1 = r.single().toShort()
                poke.move2 = r.single().toShort()
                poke.move3 = r.single().toShort()
                poke.move4 = r.single().toShort()
            }
            5 -> poke.moveSelected = r.single().toShort()
            6 -> poke.number = r.single().toShort()
            7 -> poke.targetType = r.single().toShort()
            8 -> poke.position = r.single()
            9 -> poke.tag = r.rawStr()
        }
    }

    /** Consume a change record without applying (for released pokemon). */
    private fun consumeReason(r: Reader) {
        when (r.single()) {
            1 -> { r.single(); r.double(); r.single(); r.single(); r.single(); r.single(); r.single(); r.single(); r.single(); r.single(); r.single(); r.rawStr() }
            10 -> { r.single(); r.double(); r.single(); r.single(); r.single(); r.single(); r.single(); r.single(); r.single(); r.single() }
            2 -> { r.single() }
            3 -> { r.double() }
            4 -> { r.single(); r.single(); r.single(); r.single() }
            5 -> { r.single() }
            6 -> { r.single() }
            7 -> { r.single() }
            8 -> { r.single() }
            9 -> { r.rawStr() }
        }
    }
}

/** Respond raw urlencoded text, bypassing ContentNegotiation (which is wired for form bodies). */
private suspend fun ApplicationCall.respondForm(response: List<Pair<String, String>>) {
    respondText(response.formUrlEncode(), ContentType.Text.Plain)
}

suspend fun handleD88bSave(call: ApplicationCall) {
    val log = call.application.log

    // Read the raw body directly: ContentNegotiation is registered with a form-urlencoded
    // converter (for the migration page), which otherwise consumes the body before
    // receiveParameters() can parse it. The raw channel bypasses that.
    val rawBody = call.receiveChannel().readRemaining().readText()
    val parameters = (call.request.queryString() + "&" + rawBody).parseUrlEncodedParameters()

    val action = parameters["Action"]
    val email = parameters["Email"]
    val password = parameters["Pass"]
    log.info("[D88B] action=$action email=$email")

    if (action == null || email == null || password == null) {
        call.respondForm(ReasonsEnum.FAILURE_NOT_ALL_PARAMETERS_SUPPLIED.fullReason())
        return
    }

    if (action == "createAccount") {
        if (PTD1SWFController.userExists(email)) {
            call.respondForm(ReasonsEnum.FAILURE_TAKEN.fullReason())
            return
        }

        transaction {
            val user = getOrCreateUser(email, password)
            val ptd1User = PTD1User.new { this.user = user.id }
            PTD1Achievement.new { this.user = ptd1User.id }
            for (i in 0..<3) {
                PTD1Save.new {
                    this.user = ptd1User.id
                    number = i.toByte()
                    version = SaveVersionEnum.RED
                }
            }
        }

        val user = transaction {
            PTD1User.find(PTD1Users.user eq getUser(email)!!.id)
                .with(PTD1User::saves, PTD1Save::pokemon, PTD1Save::items).first()
        }
        call.respondForm(PTD1D88b.loadAccount(user))
        return
    }

    if (!validCredentials(email, password)) {
        call.respondForm(ReasonsEnum.FAILURE_NOT_FOUND.fullReason())
        return
    }

    val ptdUser = getOrCreateUser(email, password)
    val user = transaction {
        PTD1User.find(PTD1Users.user eq ptdUser.id)
            .with(PTD1User::saves, PTD1Save::pokemon, PTD1Save::items).first()
    }

    when (action) {
        "loadAccount" -> call.respondForm(PTD1D88b.loadAccount(user))
        "saveAccount" -> {
            try {
                call.respondForm(PTD1D88b.saveAccount(user, parameters))
            } catch (e: Exception) {
                log.error("[D88B] saveAccount failed (saveString=${parameters["saveString"]})", e)
                call.respondForm(listOf("Result" to "Failure", "Reason" to "Validation:server"))
            }
        }
        else -> call.respondForm(ReasonsEnum.FAILURE_NOT_ALL_PARAMETERS_SUPPLIED.fullReason())
    }
}
