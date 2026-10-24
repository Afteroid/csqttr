package com.csqtt.client.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контракт телефонной части установки: отказ на частном адресе до SSH и проверка
 * домена до первой загрузки. Пути читаются так же, как в `DeployScriptPreflightTest`,
 * поэтому тест запускается только через Gradle.
 */
class DeployDomainCheckContractTest {
    private fun deployOperations(): String {
        val candidates = listOf(
            File("app/src/main/java/com/csqtt/client/ui/DeployOperations.kt"),
            File("src/main/java/com/csqtt/client/ui/DeployOperations.kt"),
        )
        return candidates.first(File::isFile).readText().replace("\r\n", "\n")
    }

    private fun performDeployBody(): String {
        val operations = deployOperations()
        val start = operations.indexOf("internal suspend fun performDeploy(")
        val end = operations.indexOf("internal suspend fun performUninstall(")
        assertTrue("не найден performDeploy", start >= 0)
        assertTrue("не найден performUninstall после performDeploy", end > start)
        return operations.substring(start, end)
    }

    @Test
    fun `private address is rejected before the ssh connection`() {
        val body = performDeployBody()
        val check = body.indexOf("PanelHostPolicy.isPrivateHost(panelHost)")
        val connect = body.indexOf("createSSHClient(")
        assertTrue("нет проверки частного адреса", check >= 0)
        assertTrue("SSH-подключение не найдено", connect >= 0)
        assertTrue("проверка частного адреса должна идти до createSSHClient", check < connect)
        assertTrue(body.contains("недостижим с TURN-релея и из интернета"))
    }

    @Test
    fun `domain is verified before the first upload`() {
        val body = performDeployBody()
        val verify = body.indexOf("verifyPanelDomain(")
        val upload = body.indexOf("sshClient.upload(")
        assertTrue("нет проверки домена", verify >= 0)
        assertTrue("загрузка на сервер не найдена", upload >= 0)
        assertTrue("проверка домена должна идти до первой загрузки", verify < upload)
        assertTrue(
            "проверка домена выполняется только для доменного хоста",
            body.contains("PanelHostPolicy.isDomainHost(panelHost)"),
        )
    }

    @Test
    fun `domain probe asks for both address families in one command`() {
        val operations = deployOperations()
        assertEquals(
            "getent должен опрашивать обе семьи одним вызовом",
            1,
            Regex(Regex.escape("getent ahosts")).findAll(operations).count(),
        )
        assertFalse("раздельный ahostsv4 не используется", operations.contains("ahostsv4"))
        assertFalse("раздельный ahostsv6 не используется", operations.contains("ahostsv6"))
        assertTrue("нет публичного IP сервера", operations.contains("EGRESS_IP="))
        assertTrue("нет IPv4-записей", operations.contains("\\nA=%s"))
        assertTrue("нет IPv6-записей", operations.contains("AAAA=%s"))
        assertTrue("семья определяется по наличию ':'", operations.contains("it.contains(':')"))
    }

    @Test
    fun `domain check retries three times with a five second pause`() {
        val operations = deployOperations()
        assertTrue(operations.contains("DOMAIN_CHECK_ATTEMPTS = 3"))
        assertTrue(operations.contains("DOMAIN_CHECK_DELAY_MS = 5000L"))
        assertTrue(operations.contains("repeat(DOMAIN_CHECK_ATTEMPTS)"))
        assertTrue(operations.contains("kotlinx.coroutines.delay(DOMAIN_CHECK_DELAY_MS)"))
    }

    @Test
    fun `domain check rejects every aaaa record`() {
        val operations = deployOperations()
        assertTrue(operations.contains("есть AAAA-запись; панель работает только по IPv4, удалите её"))
        assertTrue(operations.contains("A-запись для \$host не найдена"))
        assertTrue(operations.contains("Не удалось определить публичный IPv4 сервера"))
        assertTrue(operations.contains("выключите проксирование (серое облако)"))
    }

    @Test
    fun `host is normalized before it becomes the public address`() {
        val operations = deployOperations()
        assertTrue(operations.contains("PanelHostPolicy.normalizeHost(host)"))
        assertTrue(
            "CSQTT_PUBLIC_IP в env-файле берётся из нормализованного хоста",
            operations.contains("CSQTT_PUBLIC_IP=\${deployEnvironmentValue(panelHost, installInDocker)}"),
        )
        assertTrue(
            "CSQTT_PUBLIC_IP в окружении установщика берётся из нормализованного хоста",
            operations.contains("CSQTT_PUBLIC_IP=\$panelHost "),
        )
        assertFalse("сырой host больше не попадает в env-файл", operations.contains("deployEnvironmentValue(host.trim()"))
    }
}
