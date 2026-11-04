package com.senzaproject.senzatools.platform.root
// Hanya untuk rangkaian uji di luar Android: SelinuxControl asli membutuhkan AccessManager. parseMode aslinya = SelinuxMode.parse.
object SelinuxControl { fun parseMode(t: String?): String? = SelinuxMode.parse(t) }
