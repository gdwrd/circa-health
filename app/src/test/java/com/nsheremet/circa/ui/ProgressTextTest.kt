package com.nsheremet.circa.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressTextTest {
    @Test fun midway() = assertEquals("Downloading: 41% (1.1 of 2.6 GB)", progressText(1_061_140_562, 2_588_147_712))
    @Test fun start() = assertEquals("Downloading: 0% (0.0 of 3.7 GB)", progressText(0, 3_659_530_240))
    @Test fun done() = assertEquals("Downloading: 100% (2.6 of 2.6 GB)", progressText(2_588_147_712, 2_588_147_712))
    @Test fun unknownTotal() = assertEquals("Downloading: 0% (0.5 of 0.0 GB)", progressText(500_000_000, 0))
}
