package com.example.pickupdrop

import kotlin.math.*

data class Geo(val lat: Double, val lon: Double) {
    fun valid() = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
    fun near(other: Geo, meters: Double = 30.0) = valid() && other.valid() && distance(other) <= meters
    fun distance(other: Geo): Double {
        val a = Math.toRadians(lat); val b = Math.toRadians(other.lat)
        val d = Math.toRadians(other.lat-lat); val e = Math.toRadians(other.lon-lon)
        return 6371000.0 * 2 * asin(sqrt((sin(d/2).pow(2)+cos(a)*cos(b)*sin(e/2).pow(2)).coerceIn(0.0,1.0)))
    }
    fun bearing(other: Geo): Double {
        val a=Math.toRadians(lat); val b=Math.toRadians(other.lat); val d=Math.toRadians(other.lon-lon)
        return (Math.toDegrees(atan2(sin(d)*cos(b),cos(a)*sin(b)-sin(a)*cos(b)*cos(d)))+360)%360
    }
}
data class Anchor(val name: String, val geo: Geo)
data class Spot(
    val id: String, val name: String, val geo: Geo, val walkSec: Int, val walkGuide: String,
    val waiting: Boolean, val camera: Boolean?, val busLane: Boolean?, val intersection: Boolean?,
    val source: String, val checkedAt: Long = 0L
) {
    val demo get() = source == "DEMO"
    val restrictionsKnown get() = camera != null && busLane != null && intersection != null
}
data class Ranked(val spot: Spot, val score: Int, val access: Int, val waiting: Int, val walking: Int, val tags: List<String>)
data class Passenger(val id: String, val name: String, val spot: Spot)
data class Eta(val seconds: Int, val distance: Int, val sampledAt: Long, val arrivalAt: Long)
data class Timing(val arrivalAt: Long, val leaveAt: Long, val leaveNow: Boolean)

object PickupPolicy {
    const val SIGNAL_MAX_AGE_MS = 6000L
    const val ETA_MAX_AGE_MS = 15000L
    const val WALK_MAX_SEC = 180
    const val BUFFER_SEC = 30
    val anchors = listOf(Anchor("수원역",Geo(37.2664,126.9997)), Anchor("서울역",Geo(37.5547,126.9707)), Anchor("판교역",Geo(37.3948,127.1112)))
    fun parked(speed: Float?, park: Boolean, speedAgeMs: Long, gearAgeMs: Long): Boolean =
        speed != null && speed.isFinite() && abs(speed) <= 0.01f && park && speedAgeMs in 0..SIGNAL_MAX_AGE_MS && gearAgeMs in 0..SIGNAL_MAX_AGE_MS
    fun excluded(s: Spot, demoAllowed: Boolean): String? = when {
        !s.geo.valid() -> "유효하지 않은 좌표"
        s.demo && !demoAllowed -> "시연 데이터"
        !s.demo && (s.source != "USER_CONFIRMED" || s.checkedAt <= 0) -> "출처 확인 필요"
        !s.restrictionsKnown -> "규제 정보 미확인"
        s.camera == true -> "단속 CCTV 구역"
        s.busLane == true -> "버스전용차로"
        s.intersection == true -> "교차로"
        s.walkSec !in 1..WALK_MAX_SEC -> "도보 3분 초과 또는 미확인"
        else -> null
    }
    fun rank(spots: List<Spot>, car: Geo?, heading: Double?, demo: Boolean): List<Ranked> {
        val eligible = spots.filter { excluded(it,demo)==null }
        val bestWalk = eligible.minOfOrNull { it.walkSec }
        val base=eligible.map { s ->
            val access = if(car != null && heading != null && heading.isFinite()) {
                val difference = abs(((car.bearing(s.geo)-heading+540)%360)-180)
                (100*(1-difference/180)).roundToInt().coerceIn(0,100)
            } else 50
            val waiting = if(s.waiting) 100 else 0
            val walking = (100*(1-s.walkSec.toDouble()/WALK_MAX_SEC)).roundToInt().coerceIn(0,100)
            Ranked(s,(access*.5+waiting*.3+walking*.2).roundToInt(),access,waiting,walking,
                buildList { if(s.waiting) add("#대기여유"); if(s.walkSec==bestWalk) add("#가장가까움") })
        }.sortedWith(compareByDescending<Ranked>{it.score}.thenBy{it.spot.id})
        val easiest=base.maxOfOrNull{it.access}
        return base.map { if(it.access==easiest) it.copy(tags=it.tags+"#진입편리") else it }.take(3)
    }
    fun timing(eta: Eta?, walkSec: Int, wallNow: Long, monoNow: Long): Timing? {
        if(eta==null || eta.seconds<0 || monoNow-eta.sampledAt !in 0..ETA_MAX_AGE_MS || walkSec !in 1..WALK_MAX_SEC) return null
        val leave=eta.arrivalAt-(walkSec+BUFFER_SEC)*1000L
        return Timing(eta.arrivalAt,leave,leave<=wallNow)
    }
    fun nextIndex(size: Int, boarded: Set<Int>) = (0 until size).firstOrNull { it !in boarded }
    fun routeMatches(expected: List<Spot>, destination: Geo?, waypoints: List<Geo>?): Boolean =
        expected.isNotEmpty() && destination!=null && expected.last().geo.near(destination) &&
        (expected.size==1 || (waypoints!=null && waypoints.size==expected.size-1 && expected.dropLast(1).zip(waypoints).all{(a,b)->a.geo.near(b)}))
    fun parseAnchor(text: String): Anchor? = anchors.firstOrNull { text.replace(" ","").contains(it.name) }
    fun parseNumber(text: String): Int? = when(text.replace(" ","")) {
        "1","1번","일번","첫번째" -> 0; "2","2번","이번","두번째" -> 1; "3","3번","삼번","세번째" -> 2; else -> null
    }
    fun demoSpots(anchor: Anchor): List<Spot> {
        fun s(id: String, dx: Double, dy: Double, walk: Int, wait: Boolean, camera: Boolean=false, bus: Boolean=false, cross: Boolean=false)=
            Spot(anchor.name+id,anchor.name+" · 시연 후보 "+id,Geo(anchor.geo.lat+dx,anchor.geo.lon+dy),walk,
                "[예시] "+anchor.name+" 출구에서 후보 표식 방향으로 이동 · 실제 출구·보행로 확인 필요",wait,camera,bus,cross,"DEMO")
        return listOf(s("A",.0006,.0008,120,true),s("B",-.0005,.0005,60,false),s("C",.0002,-.0009,180,true),
            s("D",.0004,.0003,60,true,camera=true),s("E",-.0003,-.0005,90,true,bus=true),s("F",.0001,.0002,45,true,cross=true),s("G",.002,.002,240,true))
    }
}
