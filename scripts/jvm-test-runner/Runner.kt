// Ejecuta todos los métodos @org.junit.Test de las clases indicadas e informa del resultado.
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    var ok = 0; var fail = 0
    val t0 = System.currentTimeMillis()
    for (cn in args) {
        val cls = Class.forName(cn)
        println("▶ ${cls.simpleName}")
        for (m in cls.declaredMethods.filter { it.isAnnotationPresent(org.junit.Test::class.java) }.sortedBy { it.name }) {
            val s = System.currentTimeMillis()
            try {
                m.invoke(cls.getDeclaredConstructor().newInstance())
                ok++; println("  ✔ ${m.name} (${System.currentTimeMillis() - s} ms)")
            } catch (e: java.lang.reflect.InvocationTargetException) {
                fail++; println("  ✘ ${m.name}: ${e.targetException}"); e.targetException.printStackTrace(System.out)
            }
        }
    }
    println("\nResultado: $ok correctas, $fail fallidas (${(System.currentTimeMillis() - t0) / 1000.0} s)")
    exitProcess(if (fail == 0) 0 else 1)
}
