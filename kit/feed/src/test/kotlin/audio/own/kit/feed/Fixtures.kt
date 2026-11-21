package audio.own.kit.feed

import org.kxml2.io.KXmlParser
import java.time.Instant

internal fun fixture(name: String): ByteArray =
    requireNotNull(object {}.javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }.use { it.readBytes() }

internal val testParser = FeedParser { KXmlParser() }

internal fun date(iso: String): Instant = Instant.parse(iso)
