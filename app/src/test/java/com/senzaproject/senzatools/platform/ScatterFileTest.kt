package com.senzaproject.senzatools.platform

import com.senzaproject.senzatools.platform.flashtool.ScatterFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScatterFileTest {

    private val sample = """
        ############################################
        #  General Setting
        ############################################
        - general: MTK_PLATFORM_CFG
          info:
            - config_version: V1.1.2
              platform: MT6789
              project: x6882
              storage: EMMC
              boot_channel: MSDC_0
        ############################################
        #  Layout Setting
        ############################################
        - partition_index: SYS0
          partition_name: preloader
          file_name: preloader_x6882.bin
          is_download: true
          linear_start_addr: 0x0
          partition_size: 0x40000
          region: EMMC_BOOT1
        - partition_index: SYS1
          partition_name: pgpt
          file_name: NONE
          is_download: true
          linear_start_addr: 0x0
          partition_size: 0x80000
          region: EMMC_USER
        - partition_index: SYS2
          partition_name: boot
          file_name: boot.img
          is_download: true
          linear_start_addr: 0x4000000
          partition_size: 0x2000000
          region: EMMC_USER
        - partition_index: SYS3
          partition_name: userdata
          file_name: userdata.img
          is_download: false
          linear_start_addr: 0x80000000
          partition_size: 0x100000000
          region: EMMC_USER
    """.trimIndent()

    @Test
    fun parsesGeneralAndPartitions() {
        val r = ScatterFile.parse(sample) as ScatterFile.Result.Ok
        assertEquals("MT6789", r.scatter.platform)
        assertEquals("EMMC", r.scatter.storage)
        assertEquals("V1.1.2", r.scatter.configVersion)
        assertEquals(4, r.scatter.partitions.size)
        val boot = r.scatter.partitions.first { it.name == "boot" }
        assertEquals(0x4000000L, boot.linearStart)
        assertEquals(0x2000000L, boot.size)
    }

    @Test
    fun flashableSkipsNoneAndNonDownload() {
        val r = ScatterFile.parse(sample) as ScatterFile.Result.Ok
        val names = r.scatter.partitions.filter { it.flashable }.map { it.name }
        assertEquals(listOf("preloader", "boot"), names)
    }

    @Test
    fun parsesLargeHexAsUnsigned() {
        val r = ScatterFile.parse(sample) as ScatterFile.Result.Ok
        assertEquals(0x100000000L, r.scatter.partitions.first { it.name == "userdata" }.size)
    }

    @Test
    fun rejectsNonScatterText() {
        assertTrue(ScatterFile.parse("hello world") is ScatterFile.Result.Error)
        assertTrue(ScatterFile.parse("   ") is ScatterFile.Result.Error)
    }

    @Test
    fun formatsSizes() {
        assertEquals("256 KB", ScatterFile.formatSize(0x40000))
        assertEquals("-", ScatterFile.formatSize(null))
    }
}
