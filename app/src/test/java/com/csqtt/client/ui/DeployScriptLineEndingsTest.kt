package com.csqtt.client.ui.legacy

import org.junit.Assert.assertEquals
import org.junit.Test

class DeployScriptLineEndingsTest {
    @Test
    fun `Windows checkout script is safe for bash after extraction`() {
        assertEquals("#!/bin/bash\n\nset -Eeuo pipefail\n", normalizeDeployScript("#!/bin/bash\r\n\r\nset -Eeuo pipefail\r\n"))
    }

    @Test
    fun `UTF8 BOM and lone carriage returns are removed`() {
        assertEquals("#!/bin/bash\nset -e\n", normalizeDeployScript("\uFEFF#!/bin/bash\rset -e\r"))
    }

    @Test
    fun `Linux script keeps shell syntax unchanged`() {
        val script = "#!/bin/bash\nset -Eeuo pipefail\nprintf '%s\\n' \"\${HOME}\"\n"
        assertEquals(script, normalizeDeployScript(script))
    }
}
