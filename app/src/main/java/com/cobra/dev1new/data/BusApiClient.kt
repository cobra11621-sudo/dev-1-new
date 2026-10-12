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
    private val tagoStationCache = mutableMapOf<String, TagoStation>()
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
            // TAGO is queried first for every registered bus stop. Its arrival time and
            // remaining-stop count are fields from one response item, so the two values
            // cannot accidentally describe different buses. City APIs are fallback only.
            val tagoArrivals = query.tagoStop
                ?.let { target -> tagoArrivals(key, target, query.routeNumbers) }
                .orEmpty()
            val result = if (tagoArrivals.isNotEmpty()) {
                BusFetchResult(tagoArrivals, source = "tago")
            } else when (query.provider) {
                Provider.DAEJEON -> fetchDaejeon(query, key)
                Provider.DAEGU -> BusFetchResult(fetchDaegu(query, key), source = "daegu")
            }
            // Public APIs do not guarantee that their items are ETA order.  In particular,
            // two acceptable routes at one stop (for example 316 and 617) must show the
            // bus arriving sooner first.
            val soonestFirst = result.arrivals
                .sortedBy { arrival ->
                    arrival.remainingSeconds
                        ?: arrival.remainingMinutes?.times(60)?.toLong()
                        ?: Long.MAX_VALUE
                }
                .take(2)
            BusArrivalSnapshot(soonestFirst, now, result.source)
        }

    private fun fetchDaejeon(query: BusQuery, rawKey: String): BusFetchResult {
        val url = buildUrl(
            "https://apis.data.go.kr/6300000/arrive/getArrInfoByUid",
            mapOf("serviceKey" to decodeIfUrlEncoded(rawKey), "arsId" to query.stopId)
        )
        val xml = getText(url)
        val records = parseDaejeonXml(xml).filter { record ->
            query.routeNumbers.any { expected ->
                sameRouteNumber(record["ROUTE_NO"].orEmpty(), expected)
            }
        }
        val arrivals = records.mapNotNull { record ->
            val seconds = record["EXTIME_SEC"]?.toLongOrNull()
            val minutes = record["EXTIME_MIN"]?.toIntOrNull()
            val stops = STOP_COUNT_FIELDS.asSequence()
                .mapNotNull { record[it]?.toIntOrNull() }
                .firstOrNull()
            if (seconds == null && minutes == null) null
            else VehicleArrival(
                remainingSeconds = seconds,
                remainingMinutes = if (seconds == null) minutes else null,
                remainingStops = stops
            )
        }
        return BusFetchResult(arrivals, source = "daejeon")
    }

    /**
     * TAGO is the primary source for every bus route in RouteCatalog. It supplies the
     * ETA and remaining-station count from the same arrival record.
     */
    private fun tagoArrivals(
        rawKey: String,
        target: TagoStopTarget,
        routeNumbers: List<String>
    ): List<VehicleArrival> = runCatching {
        val station = synchronized(tagoStationCache) { tagoStationCache[target.cacheKey] }
            ?: findTagoStation(rawKey, target)
            ?: return emptyList()

        val arrivals = parseTagoXml(getText(buildUrl(
            TAGO_ARRIVAL_URL,
            mapOf(
                "serviceKey" to decodeIfUrlEncoded(rawKey),
                "pageNo" to "1",
                "numOfRows" to "20",
                "_type" to "xml",
                "cityCode" to target.cityCode,
                "nodeId" to station.nodeId
            )
        )))
        val routeItems = arrivals.filter { item ->
            routeNumbers.any { expected ->
                sameRouteNumber(item["ROUTENO"].orEmpty(), expected)
            }
        }
        routeItems.mapNotNull { item ->
            val seconds = item["ARRTIME"]?.toLongOrNull()
            val stops = item["ARRPREVSTATIONCNT"]?.toIntOrNull()
            if (seconds == null) null else VehicleArrival(
                remainingSeconds = seconds,
                remainingStops = stops
            )
        }
    }.getOrDefault(emptyList())

    private fun findTagoStation(rawKey: String, target: TagoStopTarget): TagoStation? {
        val stationRecords = parseTagoXml(getText(buildUrl(
            TAGO_NEARBY_STATION_URL,
            mapOf(
                "serviceKey" to decodeIfUrlEncoded(rawKey),
                "pageNo" to "1",
                "numOfRows" to "20",
                "_type" to "xml",
                "cityCode" to target.cityCode,
                "gpsLati" to target.latitude,
                "gpsLong" to target.longitude
            )
        )))
        val station = stationRecords
            .filter { record ->
                normalizeStopName(record["NODENM"].orEmpty()) == normalizeStopName(target.stopName)
            }
            .minByOrNull { record ->
                coordinateDistanceSquared(
                    record["GPSLATI"]?.toDoubleOrNull(),
                    record["GPSLONG"]?.toDoubleOrNull(),
                    target
                )
            }
            ?: return null
        val nodeId = station["NODEID"].orEmpty()
        if (nodeId.isBlank()) return null
        return TagoStation(nodeId).also { found ->
            synchronized(tagoStationCache) { tagoStationCache[target.cacheKey] = found }
        }
    }

    private fun coordinateDistanceSquared(latitude: Double?, longitude: Double?, target: TagoStopTarget): Double {
        if (latitude == null || longitude == null) return Double.MAX_VALUE
        val lat = latitude - target.latitude.toDouble()
        val lng = longitude - target.longitude.toDouble()
        return lat * lat + lng * lng
    }

    private fun sameRouteNumber(actual: String, expected: String): Boolean =
        normalizeRouteNumber(actual) == normalizeRouteNumber(expected)

    private fun normalizeRouteNumber(value: String): String =
        value.trim().removeSuffix("번").replace(" ", "").uppercase()

    /** TAGO and city APIs occasionally differ only in spaces, parentheses, or slashes. */
    private fun normalizeStopName(value: String): String =
        value.filter { it.isLetterOrDigit() }

    private fun fetchDaegu(query: BusQuery, rawKey: String): List<VehicleArrival> {
        val url = buildUrl(
            "https://apis.data.go.kr/6270000/dbmsapi02/getRealtime02",
            mapOf(
                "serviceKey" to decodeIfUrlEncoded(rawKey),
                "bsId" to query.stopId,
                "routeNo" to query.routeNumbers.single()
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
    private data class BusFetchResult(
        val arrivals: List<VehicleArrival>,
        val source: String
    )
    private data class TagoStation(val nodeId: String)
    private data class TagoStopTarget(
        val cityCode: String,
        val stopName: String,
        val latitude: String,
        val longitude: String
    ) {
        val cacheKey: String get() = "$cityCode:$stopName@$latitude,$longitude"
    }
    private data class BusQuery(
        val provider: Provider,
        val stopId: String,
        val routeNumbers: List<String>,
        val tagoStop: TagoStopTarget? = null
    )

    private fun queryFor(journeyId: JourneyId, legId: String): BusQuery {
        val pair = when (journeyId to legId) {
            JourneyId.COMMUTE to "commute_514" -> Triple(Provider.DAEJEON, "32620", listOf("514"))
            JourneyId.COMMUTE to "commute_donggu4_1" -> Triple(Provider.DAEGU, "7011051500", listOf("동구4-1"))
            JourneyId.RETURN to "return_donggu4" -> Triple(Provider.DAEGU, "7011051600", listOf("동구4"))
            JourneyId.RETURN to "return_514" -> Triple(Provider.DAEJEON, "10040", listOf("514"))
            JourneyId.HOSPITAL_OUTBOUND to "hospital_donggu4" -> Triple(Provider.DAEGU, "7011051600", listOf("동구4"))
            JourneyId.HOSPITAL_RETURN to "hospital_donggu4_1" -> Triple(Provider.DAEGU, "7011032600", listOf("동구4-1"))
            JourneyId.DAEJEON_DENTAL_OUTBOUND to "daejeon_dental_outbound" -> Triple(Provider.DAEJEON, "32620", listOf("316", "617"))
            JourneyId.DAEJEON_DENTAL_RETURN to "daejeon_dental_return" -> Triple(Provider.DAEJEON, "32220", listOf("316", "617"))
            else -> throw BusApiException("등록되지 않은 버스 경로입니다.")
        }
        val provider = pair.first
        return BusQuery(
            provider = provider,
            stopId = pair.second,
            routeNumbers = pair.third,
            tagoStop = when (journeyId to legId) {
                JourneyId.COMMUTE to "commute_514" -> TagoStopTarget(
                    cityCode = TAGO_DAEJEON_CITY_CODE,
                    stopName = "한밭초등학교",
                    latitude = "36.3551065",
                    longitude = "127.3951275"
                )
                JourneyId.RETURN to "return_514" -> TagoStopTarget(
                    cityCode = TAGO_DAEJEON_CITY_CODE,
                    stopName = "대전역/역전시장",
                    latitude = "36.3295844",
                    longitude = "127.4336672"
                )
                JourneyId.COMMUTE to "commute_donggu4_1" -> TagoStopTarget(
                    cityCode = TAGO_DAEGU_CITY_CODE,
                    stopName = "안심역(1번출구)",
                    latitude = "35.8724067",
                    longitude = "128.7335650"
                )
                JourneyId.RETURN to "return_donggu4",
                JourneyId.HOSPITAL_OUTBOUND to "hospital_donggu4" -> TagoStopTarget(
                    cityCode = TAGO_DAEGU_CITY_CODE,
                    stopName = "대구경북지방병무청",
                    latitude = "35.8770672",
                    longitude = "128.7351894"
                )
                JourneyId.HOSPITAL_RETURN to "hospital_donggu4_1" -> TagoStopTarget(
                    cityCode = TAGO_DAEGU_CITY_CODE,
                    stopName = "동호육교2",
                    latitude = "35.8668332",
                    longitude = "128.7245359"
                )
                JourneyId.DAEJEON_DENTAL_OUTBOUND to "daejeon_dental_outbound" -> TagoStopTarget(
                    cityCode = TAGO_DAEJEON_CITY_CODE,
                    stopName = "한밭초등학교",
                    latitude = "36.3551065",
                    longitude = "127.3951275"
                )
                JourneyId.DAEJEON_DENTAL_RETURN to "daejeon_dental_return" -> TagoStopTarget(
                    cityCode = TAGO_DAEJEON_CITY_CODE,
                    stopName = "SK브로드밴드",
                    latitude = "36.3511855",
                    longitude = "127.3804412"
                )
                else -> null
            }
        )
    }

    private fun parseTagoXml(xml: String): List<Map<String, String>> {
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())
        val result = mutableListOf<Map<String, String>>()
        var record: MutableMap<String, String>? = null
        var fieldName = ""
        var event = parser.eventType
        var resultCode: String? = null
        var resultMessage: String? = null
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    fieldName = parser.name.uppercase()
                    if (fieldName == "ITEM") record = linkedMapOf()
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text.trim()
                    if (text.isNotBlank()) {
                        record?.let { it[fieldName] = it.getOrDefault(fieldName, "") + text }
                        if (fieldName == "RESULTCODE") resultCode = text
                        if (fieldName == "RESULTMSG") resultMessage = text
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("item", ignoreCase = true)) {
                        record?.let(result::add)
                        record = null
                    }
                    fieldName = ""
                }
            }
            event = parser.next()
        }
        if (resultCode != null && resultCode !in setOf("0", "00")) {
            throw BusApiException(resultMessage?.take(160) ?: "TAGO 버스 API가 오류를 반환했습니다.")
        }
        return result
    }

    private companion object {
        val STOP_COUNT_FIELDS = listOf("REMAIN_STOP", "REMAIN_STOP_CNT", "BS_GAP")
        const val TAGO_NEARBY_STATION_URL = "https://apis.data.go.kr/1613000/BusSttnInfoInqireService/getCrdntPrxmtSttnList"
        const val TAGO_ARRIVAL_URL = "https://apis.data.go.kr/1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList"
        const val TAGO_DAEJEON_CITY_CODE = "25"
        const val TAGO_DAEGU_CITY_CODE = "22"
    }
}
