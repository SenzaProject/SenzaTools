package org.junit
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION) annotation class Test
object Assert {
    @JvmStatic fun assertEquals(a: Any?, b: Any?) { if (a != b) throw AssertionError("expected <$a> but was <$b>") }
    @JvmStatic fun assertNotEquals(a: Any?, b: Any?) { if (a == b) throw AssertionError("expected different but both <$a>") }
    @JvmStatic fun assertTrue(v: Boolean) { if (!v) throw AssertionError("expected true") }
    @JvmStatic fun assertFalse(v: Boolean) { if (v) throw AssertionError("expected false") }
    @JvmStatic fun assertNull(v: Any?) { if (v != null) throw AssertionError("expected null but was <$v>") }
    @JvmStatic fun assertNotNull(v: Any?) { if (v == null) throw AssertionError("expected not null") }
}
