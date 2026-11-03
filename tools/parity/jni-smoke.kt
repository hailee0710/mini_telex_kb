// Smoke test for the JNI bridge on the JVM: compiles VikeyDriver.kt + the host build of
// libvikey.so and drives it the way the IME will (apply each Edit to a StringBuilder).
// Catches packing/link mistakes without a device. Run via ./jni-smoke.sh
import com.miniqwerty.kb.Driver

private var failures = 0

private fun type(driver: Driver, text: String, field: StringBuilder, now: Double = 0.0) {
    for (c in text) {
        val e = driver.typeCharacter(c.toString(), now)
        if (e.deleteCount > 0) field.setLength(maxOf(0, field.length - e.deleteCount))
        field.append(e.text)
    }
}

fun main() {
    // One engine per process, as in the IME. A fresh field is resetContext() + setLanguage,
    // never a new Driver: the engine keeps word state in globals.
    val driver = Driver().apply {
        autoSwitch = true
        modernOrthography = false
        teencode = false
        spelling = true
        restoreIfInvalid = true
        applySettings()
    }

    fun check(input: String, expected: String) {
        driver.resetContext()
        driver.language = Driver.VIETNAMESE
        val field = StringBuilder()
        type(driver, input, field)
        type(driver, " ", field)
        val got = field.toString().trimEnd()
        val ok = got == expected
        if (!ok) failures++
        println("${if (ok) "ok  " else "FAIL"}  $input -> $got${if (ok) "" else "   expected $expected"}")
    }

    check("tieengs", "tiếng")
    check("nguowif", "người")
    check("hoaf", "hòa")
    check("quar", "quả")
    check("masy", "máy")
    check("fix", "fix")
    check("cluster", "cluster")
    check("server", "server")
    check("for", "for")
    check("dooor", "door")

    driver.close()
    println(if (failures == 0) "all cases passed" else "$failures case(s) failed")
    if (failures > 0) kotlin.system.exitProcess(1)
}
