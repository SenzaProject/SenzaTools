package android.database
interface Cursor : java.io.Closeable { fun moveToFirst(): Boolean; fun getString(i: Int): String?; fun isNull(i: Int): Boolean; fun getLong(i: Int): Long }
