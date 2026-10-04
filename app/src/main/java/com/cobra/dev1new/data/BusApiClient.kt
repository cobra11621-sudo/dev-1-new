package com.cobra.dev1new.data

import android.util.Xml
import com.cobra.dev1new.domain.ArrivalFormatter
import com.cobra.dev1new.domain.JourneyId
import com.cobra.dev1new.domain.RouteCatalog
import com.cobra.dev1new.domain.TransportKind
import com.cobra.dev1new.domain.VehicleArrival
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class BusApiException(message: String) : Exception(message)

data class BusArrivalSnapshot(
    val arrivals: List<VehicleArrival>,
    val fetchedAtEpochMillis: Long,
    val source: String
) {
    fun displayText(nowEpochMillis: Long = System.currentTimeMillis()): String =
        ArrivalFormatter.formatPlanned(arrivals, fetchedAtEpochMillis, nowEpochMillis)
}

/** Native equivalents of the Dev-1 public-data arrival calls. The key is never embedded in source. */
class BusApiClient(private val keyVault: ApiKeyVault) {
    suspend fun fetchFor(journeyId: JourneyId, legIndex: Int): BusArrivalSnapshot =
        withContext(Dispatchers.IO) {
            val route = RouteCatalog.journey(journeyId)
            val leg = route.legs.getOrNull(legIndex)
                ?: throw BusApiException("경로 구간을 찾지 못했습니다.")
            if (leg.kind != TransportKind.BUS) throw BusApiException("버스 구간이 아닙니다.")
            val query = queryFor(journeyId, leg.id)
            val key = keyVault.get()?.takeIf(String::isNotBlank)
                ?: throw BusApiException("버스 도착정보 API 키가 설정되지 않았습니다.")
            val now = System.currentTimeMillis()
            val arrivals = when (query.provider) {
                Provider.DAEJEON -> fetchDaejeon(query, key)
                Provider.DAEGU -> fetchDaegu(query, key)
            }
            BusArrivalSnapshot(arrivals.take(2), now, query.provider.name.lowercase())
        }

    private fun fetchDaejeon(query: BusQuery, rawKey: String): List<VehicleArrival> {
        val url = buildUrl(
            "https://apis.data.go.kr/6300000/arrive/getArrInfoByUid",
            mapOf("serviceKey" to decodeIfUrlEncoded(rawKey), "arsId" to query.stopId)
        )
        val xml = getText(url)
        val records = parseDaejeonXml(xml).filter { record ->
            val route = record["ROUTE_NO"].orEmpty().trim().removeSuffix("번")
            route.equals(query.routeNumber.trim().removeSuffix("번"), ignoreCase = true)
        }
        return records.mapNotNull { record ->
            val seconds = record["EXTIME_SEC"]?.toLongOrNull()
            val minutes = record["EXTIME_MIN"]?.toIntOrNull()
            val stops = sequenceOf("REMAIN_STOP", "REMAIN_STOP_CNT", "BS_GAP")
                .mapNotNull { record[it]?.toIntOrNull() }
                .firstOrNull()
            if (seconds == null && minutes == null) null
            else VehicleArrival(
                remainingSeconds = seconds,
                remainingMinutes = if (seconds == null) minutes else null,
                remainingStops = stops
            )
        }
    }

    private fun fetchDaegu(query: BusQuery, rawKey: String): List<VehicleArrival> {
        val url = buildUrl(
            "https://apis.data.go.kr/6270000/dbmsapi02/getRealtime02",
            mapOf(
                "serviceKey" to decodeIfUrlEncoded(rawKey),
                "bsId" to query.stopId,
                "routeNo" to query.routeNumber
            )
        )
        val root = JSONObject(getText(url))
        val body = root.optJSONObject("body")
            ?: root.optJSONObject("response")?.optJSONObject("body")
            ?: root
        val outer = jsonItems(body.opt("items") ?: body.opt("item") ?: body)
        val flattened = mutableListOf<JSONObject>()
        outer.forEach { route ->
            val arrivals = route.opt("arrList")
            flattened += jsonItems(arrivals)
        }
        val sourceItems = flattened.ifEmpty { outer }
        return sourceItems.mapNotNull { item ->
            val seconds = item.stringValue("arrTime")?.toLongOrNull()
            val stops = item.stringValue("bsGap")?.toIntOrNull()
            val state = item.stringValue("arrState")?.takeIf(String::isNotBlank)
            if (seconds == null && state == null) null
            else VehicleArrival(
                remainingSeconds = seconds,
                remainingStops = stops,
                stateLabel = state
            )
        }
    }

    private fun parseDaejeonXml(xml: String): List<Map<String, String>> {
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())
        val result = mutableListOf<Map<String, String>>()
        var record: MutableMap<String, String>? = null
        var fieldName = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    fieldName = parser.name
                    if (fieldName == "itemList") record = linkedMapOf()
                }
                XmlPullParser.TEXT -> if (record != null && fieldName.isNotBlank()) {
                    val current = record!!
                    current[fieldName] = current.getOrDefault(fieldName, "") + parser.text.trim()
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "itemList") {
                        record?.let(result::add)
                        record = null
                    }
                    fieldName = ""
                }
            }
            event = parser.next()
        }
        val errorCode = Regex("<headerCd>([^<]*)</headerCd>").find(xml)?.groupValues?.getOrNull(1)
        if (errorCode != null && errorCode !in setOf("0", "00")) {
            val message = Regex("<headerMsg>([^<]*)</headerMsg>").find(xml)?.groupValues?.getOrNull(1)
            throw BusApiException(message?.take(160) ?: "대전 버스 API가 오류를 반환했습니다.")
        }
        return result
    }

    private fun jsonItems(value: Any?): List<JSONObject> = when (value) {
        is JSONObject -> {
            val nested = value.opt("item") ?: value.opt("items")
            when (nested) {
                is JSONArray -> (0 until nested.length()).mapNotNull { nested.optJSONObject(it) }
                is JSONObject -> listOf(nested)
                else -> listOf(value)
            }
        }
        is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
        else -> emptyList()
    }

    private fun JSONObject.stringValue(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key) ?: return null
        return value.toString().trim().takeIf(String::isNotBlank)
    }

    private fun getText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.setRequestProperty("Accept", "application/json, application/xml, text/xml")
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            if (status !in 200..299) {
                throw BusApiException("버스 API HTTP 오류 ($status)")
            }
            text
        } catch (error: BusApiException) {
            throw error
        } catch (_: Exception) {
            throw BusApiException("버스 도착정보를 가져오지 못했습니다. 네트워크 또는 API 응답을 확인하세요.")
        } finally {
            connection.disconnect()
        }
    }

    private fun buildUrl(endpoint: String, parameters: Map<String, String>): String =
        endpoint + "?" + parameters.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    /** Public-data keys are sometimes pasted in URL-encoded form; preserve literal plus signs. */
    private fun decodeIfUrlEncoded(value: String): String {
        if (!value.contains('%')) return value
        return runCatching {
            URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        }.getOrDefault(value)
    }

    private enum class Provider { DAEJEON, DAEGU }
    private data class BusQuery(val provider: Provider, val stopId: String, val routeNumber: String)

    private fun queryFor(journeyId: JourneyId, legId: String): BusQuery {
        val pair = when (journeyId to legId) {
            JourneyId.COMMUTE to "commute_514" -> Triple(Provider.DAEJEON, "32620", "514")
            JourneyId.COMMUTE to "commute_donggu4_1" -> Triple(Provider.DAEGU, "7011051500", "동구4-1")
            JourneyId.RETURN to "return_donggu4" -> Triple(Provider.DAEGU, "7011051600", "동구4")
            JourneyId.RETURN to "return_514" -> Triple(Provider.DAEJEON, "10040", "514")
            JourneyId.HOSPITAL_OUTBOUND to "hospital_donggu4" -> Triple(Provider.DAEGU, "7011051600", "동구4")
            JourneyId.HOSPITAL_RETURN to "hospital_donggu4_1" -> Triple(Provider.DAEGU, "7011032600", "동구4-1")
            else -> throw BusApiException("등록되지 않은 버스 경로입니다.")
        }
        val provider = pair.first
        return BusQuery(provider, pair.second, pair.third)
    }
}
