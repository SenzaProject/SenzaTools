import java.lang.reflect.InvocationTargetException
fun main(args: Array<String>) {
    var pass = 0; var fail = 0
    for (cn in args) {
        val k = Class.forName(cn)
        for (m in k.declaredMethods.sortedBy { it.name }) {
            if (m.getAnnotation(org.junit.Test::class.java) == null) continue
            val t0 = System.nanoTime()
            try { m.invoke(k.getDeclaredConstructor().newInstance()); pass++; println("PASS  ${k.simpleName}.${m.name}  (${(System.nanoTime() - t0) / 1_000_000} ms)") }
            catch (e: InvocationTargetException) { fail++; println("FAIL  ${k.simpleName}.${m.name}: ${e.targetException}") }
        }
    }
    println("\n$pass lulus, $fail gagal")
    if (fail > 0) System.exit(1)
}
