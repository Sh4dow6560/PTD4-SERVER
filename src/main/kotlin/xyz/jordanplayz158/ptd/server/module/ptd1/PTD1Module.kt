package xyz.jordanplayz158.ptd.server.module.ptd1

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.dao.with
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.jordanplayz158.ptd.server.common.*
import xyz.jordanplayz158.ptd.server.module.ptd1.controller.PTD1SWFController
import xyz.jordanplayz158.ptd.server.module.ptd1.orm.PTD1Save
import xyz.jordanplayz158.ptd.server.module.ptd1.orm.PTD1User
import xyz.jordanplayz158.ptd.server.module.ptd1.orm.PTD1Users

suspend fun handleNewPoke(call: ApplicationCall) {
    val parameters = call.receiveParameters()

    val action = parameters["Action"]
    val email = parameters["Email"]
    val password = parameters["Pass"]
    val ver = parameters["Ver"]
    if (action == null || email == null || password == null || ver == null) {
        call.respondUrlEncodedForm(ReasonsEnum.FAILURE_NOT_ALL_PARAMETERS_SUPPLIED.fullReason())
        return
    }

    if (action == "createAccount") {
        call.respondUrlEncodedForm(PTD1SWFController.createAccount(email, password))
        return
    }

    if(!validCredentials(email, password)) {
        call.respondUrlEncodedForm(ReasonsEnum.FAILURE_NOT_FOUND)
        return
    }

    val ptdUser = getOrCreateUser(email, password)
    val user = transaction { PTD1User.find(PTD1Users.user eq ptdUser.id).with(PTD1User::saves, PTD1Save::pokemon, PTD1Save::items).first() }

    when (action) {
        "loadAccount" -> call.respondUrlEncodedForm(PTD1SWFController.loadAccount(user))

        "saveAccount" -> call.respondUrlEncodedForm(PTD1SWFController.saveAccount(user, parameters))
    }
}

fun Application.ptd1() {
    routing {
        route("/php") {
            post("/newPoke8.php") {
                handleNewPoke(call)
            }
        }

        // The d88b/ptd.onl client build (URLs hex-patched in the inner SWF to these short
        // root paths) speaks the ptd.ooo save.php dialect — handled by the d88b translator.
        post("/save.php") {
            handleD88bSave(call)
        }
        post("/save2.php") {
            handleD88bSave(call)
        }

        // Stubs: client polls these for achievements/mystery gift; respond neutrally
        post("/achieve.php") {
            call.respondText("Result=Success")
        }
        get("/achieve.php") {
            call.respondText("Result=Success")
        }
        post("/mystery.php") {
            call.respondText("Result=Success")
        }
        get("/mystery.php") {
            call.respondText("Result=Success")
        }
    }
}

/**
 * @return true if user exists AND user's password matches
 */
fun validCredentials(email: String, password: String) : Boolean {
    val user = getUser(email)

    if (user === null) return false

    val ptd1User = transaction { PTD1User.find(PTD1Users.user eq user.id).firstOrNull() }

    if(ptd1User === null) return false

    return passwordVerify(password, user.password)
}