package io.github.qianyeeew.dshremote.data.host

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.CoroutineScope
import java.io.File
import javax.crypto.KeyGenerator

/** 其他包的测试用的 HostRepository：DataStore 写 [file]，SecretBox 用软件密钥。 */
internal fun testHostRepository(
    file: File,
    scope: CoroutineScope,
): HostRepository {
    val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    val store =
        DataStoreFactory.create(
            serializer = HostsFileSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { HostsFile() },
            scope = scope,
            produceFile = { file },
        )
    var counter = 0
    return HostRepository(store, AesGcmSecretBox { key }) { "host-${++counter}" }
}
