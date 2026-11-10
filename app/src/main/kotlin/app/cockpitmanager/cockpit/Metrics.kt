package app.cockpitmanager.cockpit

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** One metric to sample. "rate" turns counters into per-second rates. */
data class MetricSpec(val name: String, val derive: String? = null)

/** One sample: per metric, either a single value or a value per instance (mount point, interface, ...). */
data class MetricsSample(val values: List<Map<String?, Double?>>) {
    /** The value of a non-instanced metric; null when unknown (e.g. the first rate sample). */
    fun scalar(index: Int): Double? = values.getOrNull(index)?.get(null)
    fun instances(index: Int): Map<String, Double?> =
        values.getOrNull(index)?.filterKeys { it != null }?.mapKeys { it.key!! }.orEmpty()
}

/**
 * Decodes a "metrics1" channel. 'meta' messages (objects) name each metric's
 * instances; 'data' messages (arrays) hold samples in the requested metric
 * order. Older bridges compress samples: a null or missing value means "same as
 * the previous sample". `false` means no value yet.
 */
class MetricsDecoder {
    private var instanceNames: List<List<String>?> = emptyList()
    private var last: List<List<JsonElement?>?>? = null

    fun feed(text: String): List<MetricsSample> {
        val element = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        return when (element) {
            is JsonObject -> {
                meta(element)
                emptyList()
            }
            is JsonArray -> element.mapNotNull { (it as? JsonArray)?.let(::sample) }
            else -> emptyList()
        }
    }

    private fun meta(meta: JsonObject) {
        val metrics = runCatching { meta["metrics"]?.jsonArray }.getOrNull() ?: return
        instanceNames = metrics.map { m ->
            val instances = (m as? JsonObject)?.get("instances") as? JsonArray
            instances?.map { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull().orEmpty() }
        }
        last = null
    }

    private fun sample(raw: JsonArray): MetricsSample {
        val previous = last
        val full = instanceNames.indices.map { i ->
            val value = raw.getOrNull(i)
            val prev = previous?.getOrNull(i)
            if (instanceNames[i] != null) {
                val count = instanceNames[i]!!.size
                val arr = value as? JsonArray
                List(count) { j ->
                    val v = arr?.getOrNull(j)
                    if (v == null || v is JsonNull) prev?.getOrNull(j) else v
                }
            } else {
                listOf(if (value == null || value is JsonNull) prev?.getOrNull(0) else value)
            }
        }
        last = full
        return MetricsSample(
            full.mapIndexed { i, values ->
                val names = instanceNames[i]
                if (names == null) {
                    mapOf(null to number(values.firstOrNull()))
                } else {
                    names.indices.associate { j -> names[j] to number(values.getOrNull(j)) }
                }
            },
        )
    }

    private fun number(e: JsonElement?): Double? {
        val p = e as? JsonPrimitive ?: return null
        if (p.booleanOrNull != null && !p.isString) return null // false = no value yet
        return p.doubleOrNull
    }
}

/** Live samples from Cockpit's own sampler (source "internal"), every [intervalMillis]. */
fun CockpitTransport.metrics(specs: List<MetricSpec>, intervalMillis: Int = 2000): Flow<MetricsSample> = callbackFlow {
    val options = mapOf(
        "payload" to JsonPrimitive("metrics1"),
        "source" to JsonPrimitive("internal"),
        "interval" to JsonPrimitive(intervalMillis),
        "metrics" to JsonArray(
            specs.map { s ->
                JsonObject(buildMap {
                    put("name", JsonPrimitive(s.name))
                    s.derive?.let { put("derive", JsonPrimitive(it)) }
                })
            },
        ),
    )
    val channel = channel(options)
    val decoder = MetricsDecoder()
    launch {
        for (event in channel.events) {
            when (event) {
                is ChannelEvent.Data -> decoder.feed(event.text).forEach { send(it) }
                is ChannelEvent.Control, is ChannelEvent.Bytes -> Unit
                is ChannelEvent.Closed ->
                    if (event.problem != null) close(CockpitException(Problems.describe(event.problem, event.string("message")), event.problem)) else close()
            }
        }
    }
    awaitClose { channel.close() }
}
