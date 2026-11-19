package org.koitharu.kotatsu.sync.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalJsonTest {

	private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject

	@Test
	fun sameDataSameBytes() {
		val a = obj("""{"b":1,"a":[{"k":"2","z":1},{"k":"1","y":2}]}""")
		val b = obj("""{"a":[{"y":2,"k":"1"},{"z":1,"k":"2"}],"b":1}""")
		assertEquals(CanonicalJson.encode(a), CanonicalJson.encode(b))
	}

	@Test
	fun oneRecordPerLineAndRoundTrips() {
		val text = CanonicalJson.encode(obj("""{"a":[{"k":"1"},{"k":"2"}],"c":"x\"y"}"""))
		assertEquals(7, text.trim().lines().size) // {  "a":[  rec  rec  ],  "c"  }
		assertTrue(text.contains("{\"k\":\"1\"},\n"))
		assertEquals(obj("""{"a":[{"k":"1"},{"k":"2"}],"c":"x\"y"}"""), obj(text))
	}

	@Test
	fun nestedListsKeepOrder() {
		val text = CanonicalJson.encode(obj("""{"a":[{"t":[3,1,2]}]}"""))
		assertTrue(text.contains("[3,1,2]"))
	}
}
