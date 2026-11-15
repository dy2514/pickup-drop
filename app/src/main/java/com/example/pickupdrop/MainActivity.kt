package com.example.pickupdrop

import android.Manifest
import android.app.Application
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.speech.*
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import ai.pleos.playground.navi.constants.*
import ai.pleos.playground.navi.data.*
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private lateinit var m: MeetModel
    private var recognizer: SpeechRecognizer?=null
    private val speedPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { m.connectCar() }
    private val microphone=registerForActivityResult(ActivityResultContracts.RequestPermission()) { if(it) voice() else m.notice("마이크 권한이 없어 음성 선택을 시작하지 않았습니다.") }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        m=ViewModelProvider(this)[MeetModel::class.java]; m.connectNavigation()
        if(checkSelfPermission(Car.PERMISSION_SPEED)==PackageManager.PERMISSION_GRANTED) m.connectCar() else speedPermission.launch(Car.PERMISSION_SPEED)
        setContent {
            PickupTheme {
                PickupDrop(m) { if(m.parked) { if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) voice() else microphone.launch(Manifest.permission.RECORD_AUDIO) } }
            }
        }
    }
    private fun voice() {
        if(!m.parked) { m.notice("음성으로 장소를 변경하려면 P 기어 정차가 필요합니다."); return }
        if(Build.VERSION.SDK_INT<31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            m.notice("이 기기에 온디바이스 음성 인식기가 없습니다. 거점 버튼으로 선택하세요. 음성 입력은 서버로 전송하지 않았습니다."); return
        }
        recognizer?.destroy(); recognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        recognizer!!.setRecognitionListener(object: RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { m.notice("수원역·서울역·판교역 또는 후보 번호를 말씀하세요.") }
            override fun onResults(b: Bundle?) { b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { m.utterance(it) } }
            override fun onError(code: Int) { m.notice("음성 인식 오류 $code · 버튼으로 선택할 수 있습니다.") }
            override fun onBeginningOfSpeech() {} override fun onRmsChanged(v: Float) {} override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {} override fun onPartialResults(b: Bundle?) {} override fun onEvent(t: Int,b: Bundle?) {}
        })
        recognizer!!.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ko-KR").putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true))
    }
    override fun onDestroy() { recognizer?.destroy(); super.onDestroy() }
}

enum class Stage(val label: String) { IDLE("픽업 준비"), REQUESTING("경로 요청 중"), CONFIRMED("경로 확인"), DRIVING("안내 중"), ARRIVED("도착 · 탑승 확인 대기"), CANCELING("취소 응답 대기"), CHANGED("경로 불일치"), ERROR("오류"), DELAYED("응답 지연"), RECOVERING("저장 세션 대조 중"), COMPLETE("픽업 완료") }
data class RouteEcho(val geo: Geo,val name: String,val seconds: Int,val distance: Int,val at: Long)

class MeetModel(app: Application): AndroidViewModel(app) {
    private val h=Handler(Looper.getMainLooper())
    private val prefs=app.getSharedPreferences("pickupdrop_v3",0)
    private val io=Executors.newSingleThreadExecutor()
    private val reading=AtomicBoolean(false)
    private var closed=false
    private var nav: NaviHelper?=null
    private var navListener: NaviHelperEventListener?=null
    private var navEpoch=0
    private var car: Car?=null
    private var manager: CarPropertyManager?=null
    private var carListener: CarPropertyManager.CarPropertyEventCallback?=null
    private var carEpoch=0
    private var speedAt=0L; private var gearAt=0L
    private var speed: Float?=null; private var gear: Int?=null
    private var currentGeo: Geo?=null; private var heading: Double?=null; private var positionAt=0L
    private var finalEcho: RouteEcho?=null
    private var waypointEcho: List<RouteEcho>?=null
    private var verifiedAt=0L
    private var everVerified=false
    private var conflicted=false
    private var arrivals=emptySet<Int>()
    private var lastVoiceAt=0L
    private var lastVoiceId=""
    private var tts: TextToSpeech?=null
    private var ttsReady=false
    var speechStatus by mutableStateOf("기기 음성 엔진 확인 중"); private set
    var anchor by mutableStateOf(PickupPolicy.anchors.firstOrNull { it.name==prefs.getString("anchor","") } ?: PickupPolicy.anchors[0]); private set
    var demo by mutableStateOf(prefs.getBoolean("demo",true)); private set
    var manual by mutableStateOf(loadSpots()); private set
    var ranked by mutableStateOf<List<Ranked>>(emptyList()); private set
    var selectedId by mutableStateOf(prefs.getString("selected","") ?: ""); private set
    var plan by mutableStateOf(loadPeople("plan")); private set
    var session by mutableStateOf(loadPeople("session")); private set
    var boarded by mutableStateOf(loadBoarded()); private set
    var stage by mutableStateOf(if(session.isEmpty()) Stage.IDLE else Stage.RECOVERING); private set
    var navReady by mutableStateOf(false); private set
    var navStatus by mutableStateOf("내비 연결 중"); private set
    var carStatus by mutableStateOf("차량 신호 확인 중"); private set
    var parked by mutableStateOf(false); private set
    var driving by mutableStateOf(false); private set
    var verified by mutableStateOf(false); private set
    var message by mutableStateOf(if(session.isEmpty()) "거점과 승차 후보를 선택하세요." else "저장된 경로를 현재 내비 목적지·경유지와 다시 대조합니다. 이전 ETA는 복원하지 않습니다."); private set
    var etas by mutableStateOf<Map<Int,Eta>>(emptyMap()); private set
    var clock by mutableLongStateOf(System.currentTimeMillis()); private set
    var mono by mutableLongStateOf(SystemClock.elapsedRealtime()); private set
    var location by mutableStateOf("현재 위치 수신 대기"); private set
    var events by mutableStateOf<List<String>>(emptyList()); private set
    var updateNotice by mutableStateOf("내비 ETA를 기다리는 중"); private set
    private var pollCounter=0
    val busy get()=session.isNotEmpty()
    val editable get()=parked && !busy
    val selected get()=ranked.firstOrNull{it.spot.id==selectedId} ?: ranked.firstOrNull()
    val next get()=PickupPolicy.nextIndex(session.size,boarded)
    val activePerson get()=next?.let{session.getOrNull(it)}
    val canBoard get()=parked && verified && next in arrivals && stage==Stage.ARRIVED
    val freshRoute get()=verified && !conflicted && mono-verifiedAt in 0..PickupPolicy.ETA_MAX_AGE_MS
    init {
        recompute()
        tts=TextToSpeech(app) { status -> ui { if(status==TextToSpeech.SUCCESS) {
            ttsReady=(tts?.setLanguage(Locale.KOREAN) ?: TextToSpeech.LANG_NOT_SUPPORTED)>=0
            speechStatus=if(ttsReady) "기기 음성 안내 준비됨" else "한국어 음성 엔진 없음"
        } else speechStatus="기기 음성 엔진 없음" } }
        h.post { h.post(tick) }
    }
    private fun ui(block: ()->Unit) { h.post { if(!closed) block() } }
    fun notice(text: String) { message=text; log(text) }
    private fun log(text: String) { events=(listOf(time(clock)+" · "+text)+events).take(6) }
    fun time(at: Long)=SimpleDateFormat("HH:mm:ss",Locale.KOREA).format(at)
    private fun failure(t: Throwable) { notice("요청 실패: "+(t.message ?: t.javaClass.simpleName)) }
    fun chooseAnchor(a: Anchor) { if(!editable) return; anchor=a; selectedId=""; prefs.edit().putString("anchor",a.name).apply(); recompute() }
    fun chooseDataMode(value: Boolean) { if(!editable) return; demo=value; selectedId=""; prefs.edit().putBoolean("demo",value).apply(); recompute() }
    fun select(id: String) { if(!editable || ranked.none{it.spot.id==id}) return; selectedId=id; prefs.edit().putString("selected",id).apply() }
    private fun recompute() {
        val positionFresh=SystemClock.elapsedRealtime()-positionAt in 0..15000
        ranked=PickupPolicy.rank(if(demo) PickupPolicy.demoSpots(anchor) else manual,if(positionFresh) currentGeo else null,if(positionFresh) heading else null,demo)
    }
    fun brief() {
        val top=ranked.firstOrNull() ?: return notice("규제 정보와 도보 시간을 확인한 승차 후보가 없습니다.")
        speak((if(demo) "시연용 후보입니다. " else "사용자가 확인한 후보입니다. ")+"1순위 "+top.spot.name+", 도보 약 "+top.spot.walkSec/60+"분. "+(if(top.spot.waiting) "대기 여유가 있는 것으로 입력됐습니다. " else "대기 여유가 없는 것으로 입력됐습니다. ")+"차로 접근성은 방향 근사 점수입니다. 경로 확정은 정차 후 가능합니다.")
    }
    private fun speak(text: String) { if(ttsReady) tts?.speak(text,TextToSpeech.QUEUE_FLUSH,null,"pickupdrop") else notice("음성 엔진이 없어 카드로 안내합니다.") }
    fun utterance(text: String) {
        if(!editable) return notice("주행 중에는 거점·후보 변경과 경로 요청이 잠깁니다.")
        val a=PickupPolicy.parseAnchor(text)
        if(a!=null) { chooseAnchor(a); brief(); return }
        val number=PickupPolicy.parseNumber(text)
        val choice=number?.let { ranked.getOrNull(it) }
        if(choice!=null) { select(choice.spot.id); request(false); return }
        notice("지원 거점은 수원역·서울역·판교역입니다. 거점 발화 후 1번·2번·3번으로 경로를 요청할 수 있습니다.")
    }
    fun addPerson(name: String) {
        if(!editable) return
        val spot=selected?.spot ?: return notice("추천 후보를 먼저 선택하세요.")
        if(plan.size>=3) return notice("최대 3명까지 등록할 수 있습니다.")
        if(plan.any{it.spot.geo.near(spot.geo,5.0)}) return notice("동일 좌표는 통합 경로에서 중복 등록하지 않습니다. 다른 후보를 선택하세요.")
        plan=plan+Passenger("p"+System.currentTimeMillis(),name.trim().ifBlank{"동승자 "+(plan.size+1)},spot); save()
    }
    fun move(i: Int) { if(editable && i in 0 until plan.lastIndex) { plan=plan.toMutableList().also { val p=it[i]; it[i]=it[i+1]; it[i+1]=p }; save() } }
    fun remove(i: Int) { if(editable && i in plan.indices) { plan=plan.filterIndexed{index,_->index!=i}; save() } }
    fun saveManual(name: String,lat: String,lon: String,walk: String,guide: String,wait: Boolean,checked: Boolean): Boolean {
        if(!editable || name.isBlank() || guide.isBlank() || !checked) return false
        val point=Geo(lat.toDoubleOrNull() ?: return false,lon.toDoubleOrNull() ?: return false)
        val minutes=walk.toIntOrNull() ?: return false
        if(!point.valid() || minutes !in 1..3) return false
        val s=Spot("manual"+clock,name.trim().take(60),point,minutes*60,guide.trim().take(240),wait,false,false,false,"USER_CONFIRMED",clock)
        manual=(manual+s).takeLast(20); demo=false; selectedId=s.id; prefs.edit().putBoolean("demo",false).apply(); save(); recompute(); return true
    }
    fun timing(i: Int): Timing? = if(freshRoute) PickupPolicy.timing(etas[i],session.getOrNull(i)?.spot?.walkSec ?: 0,clock,mono) else null
    fun shareText(): String? {
        if(!parked || !freshRoute) { notice("P 기어 정차와 검증된 최신 경로가 있어야 공유할 수 있습니다."); return null }
        val i=next ?: return null; val p=session[i]; val t=timing(i)
        if(t==null) { notice("현재 동승자의 최신 ETA가 없습니다. 최종 목적지 ETA를 대신 쓰지 않았습니다."); return null }
        return "[Pickup&Drop 픽업 안내 · "+p.name+"]\n"+(if(p.spot.demo) "[시연 데이터 · 실제 정차·출구 안내 미검증]\n" else "[사용자 현장 확인 정보 · 정차 가능 여부 재확인]\n")+
            "만날 곳: "+p.spot.name+"\n지도: https://www.google.com/maps/search/?api=1&query="+p.spot.geo.lat+","+p.spot.geo.lon+
            "\n도보 안내: "+p.spot.walkGuide+"\n차량 예상 도착: "+time(t.arrivalAt)+"\n권장 외출: "+(if(t.leaveNow) "지금 출발" else time(t.leaveAt)+"까지 출발")+
            "\n도보 "+p.spot.walkSec/60+"분 + 여유 30초 반영\n갱신 시각: "+time(clock)+"\n교통 상황에 따라 달라집니다. 공유된 메시지는 자동 수정되지 않습니다."
    }
    fun connectNavigation() {
        if(nav!=null) return
        val token=++navEpoch; val n=NaviHelper(getApplication()); nav=n
        val l=listener(token); navListener=l
        runCatching { n.initialize(onConnected={ui { if(token==navEpoch) {
            navReady=true; navStatus="Pleos 내비 연결됨"; n.addListener(l); refresh()
        } }},onFailed={ui { if(token==navEpoch) { navReady=false; navStatus="내비 연결 실패"; failure(it) } }}) }.onFailure{failure(it)}
    }
    fun reconnect() {
        if(!parked) return notice("서비스 재연결은 정차 후 가능합니다.")
        navEpoch++; runCatching { navListener?.let{nav?.removeListener(it)}; nav?.release() }; nav=null; navReady=false
        verified=false; etas=emptyMap(); finalEcho=null; waypointEcho=null; everVerified=false
        if(busy) { stage=Stage.RECOVERING; notice("세션을 보존하고 내비 경로를 다시 대조합니다.") }
        connectNavigation(); connectCar()
    }
    private fun listener(token: Int)=object: NaviHelperEventListener {
        private fun event(block: ()->Unit) { ui { if(token==navEpoch) block() } }
        override fun onNavigationStatus(navigationStatus: NavigationStatus) { event {
            if(freshRoute && navigationStatus==NavigationStatus.ROUTE_DRIVE && stage !in listOf(Stage.ARRIVED,Stage.CANCELING)) stage=Stage.DRIVING
        } }
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) { event { if(busy && stage!=Stage.CANCELING) log("내비 경로 탐색 시작") } }
        override fun onRoutingCompleted() { event { if(busy) refresh() } }
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) { event {
            val g=Geo(currentLocationInfo.latitude,currentLocationInfo.longitude)
            if(g.valid()) { currentGeo=g; heading=currentLocationInfo.heading; positionAt=SystemClock.elapsedRealtime(); location="차량 %.5f, %.5f · 방향 %.0f°".format(g.lat,g.lon,heading); recompute() }
        } }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) { event {
            if(!busy || stage==Stage.CANCELING || conflicted) return@event
            finalEcho=RouteEcho(Geo(destinationInfo.latitude,destinationInfo.longitude),destinationInfo.name,destinationInfo.remainTime,destinationInfo.remainDistance,SystemClock.elapsedRealtime())
            reconcile()
        } }
        override fun onWayPointInfo(waypointInfo: List<WaypointInfo>) { event {
            if(!busy || stage==Stage.CANCELING || conflicted) return@event
            waypointEcho=waypointInfo.map{RouteEcho(Geo(it.latitude,it.longitude),it.name,it.remainTime,it.remainDistance,SystemClock.elapsedRealtime())}; reconcile()
        } }
        override fun onDrivingInfo(drivingInfo: DrivingInfo) { event { if(busy && !conflicted && stage!=Stage.CANCELING) { liveDriving=drivingInfo; drivingAt=SystemClock.elapsedRealtime(); reconcile() } } }
        override fun onDestinationChanged() { event { if(busy) refresh() } }
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) { event { if(busy) refresh() } }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) { event { receiveArrival(session.lastIndex,destinationArrivedInfo.name) } }
        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) { event {
            val i=when(waypointArrivedInfo.index) { WaypointIndex.FIRST->0; WaypointIndex.SECOND->1; else->-1 }; receiveArrival(i,waypointArrivedInfo.name)
        } }
        override fun onRouteCancelled() { event { if(busy && stage==Stage.CANCELING) { clearSession(); stage=Stage.IDLE; notice("내비 취소 응답을 확인했습니다.") } } }
        override fun onError(errorCode: NaviErrorCode) { event { if(busy) { stage=Stage.ERROR; verified=false; etas=emptyMap(); notice("내비 오류: "+errorCode.name+" · 정차 후 요청을 종료하세요.") } } }
    }
    private var liveDriving: DrivingInfo?=null
    private var drivingAt=0L
    private fun sameWaypoint(s: Spot,e: RouteEcho)=if(e.geo.lat==0.0 && e.geo.lon==0.0) e.name==s.name else s.geo.near(e.geo)
    private fun reconcile() {
        if(!busy || conflicted || stage in listOf(Stage.ERROR,Stage.CANCELING)) return
        val dest=finalEcho ?: return
        val now=SystemClock.elapsedRealtime(); val wall=System.currentTimeMillis()
        if(now-dest.at>PickupPolicy.ETA_MAX_AGE_MS) return
        if(!session.last().spot.geo.near(dest.geo)) { if(everVerified || stage==Stage.RECOVERING) invalidate("최종 목적지 좌표 불일치"); return }
        val expected=session.dropLast(1).filterIndexed { i,_->i !in boarded && i !in arrivals }.map{it.spot}
        val echoes=waypointEcho
        val matching=session.size==1 || (echoes!=null && echoes.size==expected.size && expected.zip(echoes).all{(a,b)->sameWaypoint(a,b)})
        if(!matching) { verified=false; etas=emptyMap(); message="최종 목적지 또는 경유지 이름·순서 대조 중 · ETA 공유 잠금"; return }
        verified=true; everVerified=true; verifiedAt=now
        val live=liveDriving?.takeIf { now-drivingAt in 0..PickupPolicy.ETA_MAX_AGE_MS && (it.destination.name==dest.name || it.destination.name==session.last().spot.name) }
        val updated=mutableMapOf<Int,Eta>()
        fun put(i: Int,e: RouteEcho) {
            val state=live?.let{it.waypoints+it.destination}?.firstOrNull{it.name==e.name || it.name==session[i].spot.name}
            val seconds=state?.duration ?: e.seconds; val distance=state?.distance ?: e.distance
            val sampled=if(state!=null) drivingAt else e.at
            if((seconds>0 || i in arrivals) && distance>=0 && now-sampled in 0..PickupPolicy.ETA_MAX_AGE_MS)
                updated[i]=Eta(seconds,distance,sampled,wall-(now-sampled)+seconds*1000L)
        }
        if(session.lastIndex !in boarded) put(session.lastIndex,dest)
        echoes?.forEach { e->session.dropLast(1).indexOfFirst{sameWaypoint(it.spot,e)}.takeIf{it>=0}?.let{put(it,e)} }
        val i=next; val old=i?.let{etas[it]}; val fresh=i?.let{updated[it]}
        updateNotice=if(old!=null && fresh!=null && abs(old.arrivalAt-fresh.arrivalAt)>=60000) "ETA 변경으로 외출 시각 갱신 · 기존 메시지는 다시 공유하세요." else "실제 내비 ETA 갱신 · "+time(wall)
        etas=etas.filter{(index,value)->index !in boarded && now-value.sampledAt in 0..PickupPolicy.ETA_MAX_AGE_MS}+updated
        if(i in arrivals) { stage=Stage.ARRIVED; message="도착 이벤트 확인 · P 기어 정차 후 탑승 완료를 확인하세요." }
        else if(stage in listOf(Stage.REQUESTING,Stage.RECOVERING,Stage.DELAYED)) { stage=Stage.CONFIRMED; message="최종 목적지 좌표와 경유지 이름·순서를 확인했습니다." }
        save()
    }
    private fun invalidate(reason: String) { conflicted=true; verified=false; etas=emptyMap(); stage=Stage.CHANGED; notice(reason+" · 이전 ETA를 폐기했습니다. 정차 후 요청 종료·재설정이 필요합니다.") }
    private fun receiveArrival(i: Int,name: String) {
        if(!busy || !everVerified || conflicted || stage in listOf(Stage.CANCELING,Stage.ERROR) || i !in session.indices || i!=next) return
        val expectedName=if(i==session.lastIndex) finalEcho?.name else waypointEcho?.firstOrNull{it.geo.near(session[i].spot.geo)}?.name
        if(name!=session[i].spot.name && name!=expectedName) return
        arrivals=arrivals+i
        if(finalEcho?.geo?.near(session.last().spot.geo)==true) { verified=true; verifiedAt=SystemClock.elapsedRealtime(); stage=Stage.ARRIVED; notice(session[i].name+" 장소 도착 · 탑승 확인 대기") }
    }
    fun board() {
        if(!canBoard || !freshRoute) return
        val i=next ?: return; boarded=boarded+i; log(session[i].name+" 탑승 확인")
        if(next==null) { clearSession(); stage=Stage.COMPLETE; notice("모든 동승자 탑승 확인을 마쳤습니다.") }
        else { stage=Stage.CONFIRMED; etas=etas.filterKeys{it !in boarded}; notice("다음 순서 "+activePerson!!.name+" 안내가 활성화됐습니다. 최신 경유지 ETA를 기다립니다."); refresh(); save() }
    }
    fun request(multiple: Boolean) {
        if(!editable || !navReady) return; liveDriving=null; drivingAt=0L
        val people=if(multiple) plan else selected?.spot?.let{listOf(Passenger("single","동승자",it))} ?: emptyList()
        if(people.size !in 1..3) return notice("픽업 인원을 1~3명 등록하세요.")
        if(people.any{PickupPolicy.excluded(it.spot,demo)!=null}) return notice("현재 데이터 모드에서 사용할 수 없는 장소가 포함돼 있습니다.")
        session=people.toList(); boarded=emptySet(); arrivals=emptySet(); etas=emptyMap(); finalEcho=null; waypointEcho=null
        verified=false; everVerified=false; conflicted=false; stage=Stage.REQUESTING; message="내비게이션으로 요청을 전송했습니다. 응답 대기 중입니다."; save()
        fun route(s: Spot)=RouteInfo(latitude=s.geo.lat,longitude=s.geo.lon,poiName=s.name,address=s.walkGuide,poiId="",poiSubId="")
        runCatching {
            if(session.size==1) nav!!.requestRoute(route(session.single().spot))
            else nav!!.requestRouteWithWaypoints(RouteWithWaypointsRequest(route(session.last().spot),session.dropLast(1).mapIndexed { i,p->
                RequestWaypointInfo(latitude=p.spot.geo.lat,longitude=p.spot.geo.lon,poiName=p.spot.name,waypointIndex=if(i==0) WaypointIndex.FIRST else WaypointIndex.SECOND,poiId="",address=p.spot.walkGuide,poiSubId="")
            }))
            h.removeCallbacks(timeout); h.postDelayed(timeout,25000); log("경로 요청 전송 · "+session.size+"명")
        }.onFailure { stage=Stage.ERROR; failure(it) }
    }
    private val timeout=Runnable { if(stage==Stage.REQUESTING || stage==Stage.CANCELING) { if(stage!=Stage.CANCELING) stage=Stage.DELAYED; verified=false; etas=emptyMap(); notice("내비 응답 지연 · 성공으로 처리하지 않았습니다. 정차 후 재확인·요청 종료를 이용하세요.") } }
    fun cancel() { if(!parked || !navReady || !busy) return; stage=Stage.CANCELING; verified=false; etas=emptyMap(); h.removeCallbacks(timeout); h.postDelayed(timeout,25000); runCatching{nav!!.cancelRoute()}.onFailure{failure(it)} }
    fun showNavigation() { if(navReady) runCatching{nav!!.requestNavigationScreen(NavigationScreenRequest.Foreground)}.onFailure{failure(it)} }
    fun refresh() { if(navReady) runCatching { nav!!.getCurrentLocationInfo(); if(busy) { nav!!.getDestinationInfo(); if(session.size>1) nav!!.getWaypointInfo() } }.onFailure{failure(it)} }
    private fun clearSession() { session=emptyList(); boarded=emptySet(); arrivals=emptySet(); etas=emptyMap(); verified=false; everVerified=false; conflicted=false; finalEcho=null; waypointEcho=null; h.removeCallbacks(timeout); save() }
    private fun updateSafety() {
        mono=SystemClock.elapsedRealtime(); clock=System.currentTimeMillis()
        val wasDriving=driving
        parked=PickupPolicy.parked(speed,gear==VehicleGear.GEAR_PARK,mono-speedAt,mono-gearAt)
        driving=speed!=null && speed!!.isFinite() && (abs(speed!!)>0.01f || gear==VehicleGear.GEAR_DRIVE) && mono-speedAt in 0..6000 && mono-gearAt in 0..6000
        carStatus=if(mono-speedAt>6000 || mono-gearAt>6000 || speed==null || gear==null) "신호 미확인 · 조작 잠금" else "%.1f km/h · ".format(abs(speed!!)*3.6)+VehicleGear.toString(gear!!)
        if(driving && !wasDriving && clock-lastVoiceAt>60000) { lastVoiceAt=clock; brief() }
        if(!freshRoute && etas.isNotEmpty()) { updateNotice="경로·ETA 신선도 만료 · 외출 시각과 공유를 잠갔습니다." }
    }
    private val tick=object: Runnable { override fun run() {
        if(closed) return; updateSafety(); pollCounter++
        if(pollCounter%4==0) { refresh(); recompute(); readCar() }
        h.postDelayed(this,1000)
    } }
    private fun readCar() {
        val pm=manager ?: return; val token=carEpoch
        if(!reading.compareAndSet(false,true)) return
        io.execute {
            val speedResult=runCatching{pm.getProperty<Float>(VehiclePropertyIds.PERF_VEHICLE_SPEED,0)}
            val gearResult=runCatching{pm.getProperty<Int>(VehiclePropertyIds.GEAR_SELECTION,0)}
            ui { if(token==carEpoch) {
                val s=speedResult.getOrNull(); val g=gearResult.getOrNull(); val now=SystemClock.elapsedRealtime()
                speed=if(s?.status==CarPropertyValue.STATUS_AVAILABLE) s.value?.takeIf{it.isFinite()} else null
                gear=if(g?.status==CarPropertyValue.STATUS_AVAILABLE) g.value else null
                if(speed!=null) speedAt=now; if(gear!=null) gearAt=now
                updateSafety()
            }; reading.set(false) }
        }
    }
    fun connectCar() {
        val token=++carEpoch; speed=null; gear=null; parked=false; manager=null
        runCatching{carListener?.let{l->(car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager)?.unregisterCallback(l)}; car?.disconnect()}
        runCatching { car=Car.createCar(getApplication(),h,Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c,ready->ui {
            if(token==carEpoch) {
                if(!ready) { manager=null; speed=null; gear=null; updateSafety() }
                else {
                    manager=c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                    val l=object: CarPropertyManager.CarPropertyEventCallback {
                        override fun onChangeEvent(v: CarPropertyValue<*>) { ui { if(token==carEpoch) {
                            val ok=v.status==CarPropertyValue.STATUS_AVAILABLE; val now=SystemClock.elapsedRealtime()
                            if(v.propertyId==VehiclePropertyIds.PERF_VEHICLE_SPEED) { speed=if(ok) (v.value as? Number)?.toFloat()?.takeIf{it.isFinite()} else null; if(speed!=null) speedAt=now }
                            if(v.propertyId==VehiclePropertyIds.GEAR_SELECTION) { gear=if(ok) v.value as? Int else null; if(gear!=null) gearAt=now }; updateSafety()
                        } } }
                        override fun onErrorEvent(id: Int,area: Int) { ui { if(token==carEpoch) { if(id==VehiclePropertyIds.PERF_VEHICLE_SPEED) speed=null else gear=null; updateSafety() } } }
                    }
                    carListener=l
                    listOf(VehiclePropertyIds.PERF_VEHICLE_SPEED,VehiclePropertyIds.GEAR_SELECTION).forEach{id->runCatching{manager!!.registerCallback(l,id,CarPropertyManager.SENSOR_RATE_NORMAL)}.onFailure{log("车辆信号权限不足: "+it.message)}}
                    readCar()
                }
            }
        } } }.onFailure{notice("차량 연결 실패: "+it.message)}
    }
    private fun spotJson(s: Spot)=JSONObject().put("id",s.id).put("name",s.name).put("lat",s.geo.lat).put("lon",s.geo.lon).put("walk",s.walkSec).put("guide",s.walkGuide).put("waiting",s.waiting).put("camera",s.camera).put("bus",s.busLane).put("cross",s.intersection).put("source",s.source).put("checked",s.checkedAt)
    private fun spotRead(j: JSONObject)=Spot(j.getString("id"),j.getString("name"),Geo(j.getDouble("lat"),j.getDouble("lon")),j.getInt("walk"),j.getString("guide"),j.getBoolean("waiting"),if(j.isNull("camera")) null else j.getBoolean("camera"),if(j.isNull("bus")) null else j.getBoolean("bus"),if(j.isNull("cross")) null else j.getBoolean("cross"),j.getString("source"),j.optLong("checked"))
    private fun peopleJson(list: List<Passenger>)=JSONArray().also{a->list.forEach{a.put(JSONObject().put("id",it.id).put("name",it.name).put("spot",spotJson(it.spot)))}}
    private fun loadPeople(key: String): List<Passenger> = runCatching{val a=JSONArray(prefs.getString(key,"[]")); (0 until a.length()).map{val j=a.getJSONObject(it); Passenger(j.getString("id"),j.getString("name"),spotRead(j.getJSONObject("spot")))}.take(3)}.getOrDefault(emptyList())
    private fun loadSpots(): List<Spot> = runCatching{val a=JSONArray(prefs.getString("manual","[]")); (0 until a.length()).map{spotRead(a.getJSONObject(it))}.filter{it.geo.valid()}}.getOrDefault(emptyList())
    private fun loadBoarded(): Set<Int> = prefs.getStringSet("boarded",emptySet())!!.mapNotNull{it.toIntOrNull()}.filter{it in session.indices}.toSet()
    private fun save() { prefs.edit().putString("plan",peopleJson(plan).toString()).putString("session",peopleJson(session).toString()).putStringSet("boarded",boarded.map{it.toString()}.toSet()).putString("manual",JSONArray().also{a->manual.forEach{a.put(spotJson(it))}}.toString()).apply() }
    override fun onCleared() { closed=true; h.removeCallbacksAndMessages(null); navEpoch++; carEpoch++; runCatching{navListener?.let{nav?.removeListener(it)};nav?.release()};runCatching{carListener?.let{manager?.unregisterCallback(it)};car?.disconnect()};tts?.stop();tts?.shutdown();io.shutdownNow();super.onCleared() }
}

@Composable
fun PickupDrop(m: MeetModel,onVoice: ()->Unit) {
    var page by rememberSaveable{mutableIntStateOf(0)}
    var edit by rememberSaveable{mutableStateOf(false)}
    var recipient by rememberSaveable{mutableStateOf("")}
    val context=LocalContext.current
    val tabs=listOf("픽업 스팟","출발 타이밍","릴레이 픽업","연동 상태")
    Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding().padding(28.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact=maxWidth<700.dp
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(24.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Pickup&Drop",style=MaterialTheme.typography.headlineMedium)
                        Text("함께 타는 순간까지, 편안하게",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if(!compact) VehicleStatus(m.carStatus)
                }
                if(compact) VehicleStatus(m.carStatus)
            }
        }
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
        if(m.demo) Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=RoundedCornerShape(12.dp)) { Text("시연 데이터 · 실제 정차 가능 여부와 보행 경로를 확인하세요",Modifier.fillMaxWidth().padding(16.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSecondaryContainer) }
        if(!m.parked) {
            // No hidden tabs, comparison list or coordinate dialog can remain interactive after a gear change.
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Text(if(m.driving) "주행 모드 · 1순위 안내" else "차량 상태 확인 중 · 조작 잠금",style=MaterialTheme.typography.titleLarge)
            val best=m.ranked.firstOrNull()
            Panel(best?.spot?.name ?: "확인된 추천 후보 없음",best?.let{"종합 "+it.score+"점 · 도보 약 "+it.spot.walkSec/60+"분 · "+it.tags.joinToString(" ")} ?: "정차 후 장소 정보를 확인하세요.")
            Text(m.activePerson?.let{"현재 픽업: "+it.name} ?: m.stage.label)
            Text("경로 확정·편집·공유·탑승 확인은 P 기어 및 속도 0 확인 후 가능합니다.")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(12.dp)) { Button(onClick={m.brief()}) { Text("음성 브리핑") }; Button(enabled=m.navReady,onClick={m.showNavigation()}) { Text("내비 화면") } }
            }
        } else {
            BoxWithConstraints(Modifier.weight(1f)) {
              val wide=maxWidth>=1000.dp
              Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(28.dp)) {
                if(wide) Column(Modifier.width(252.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("픽업 도우미",Modifier.padding(bottom=12.dp),style=MaterialTheme.typography.titleMedium)
                    tabs.forEachIndexed { i,t -> PickupNavigationItem(i,t,page==i){page=i} }
                    Spacer(Modifier.weight(1f))
                    Text("P 기어 · 정차 상태에서 설정",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                  if(!wide) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    tabs.forEachIndexed { i,t -> PickupNavigationItem(i,t,page==i){page=i} }
                  }
                  Text(tabs[page],style=MaterialTheme.typography.headlineSmall)
                  if(m.stage==Stage.REQUESTING || m.stage==Stage.CANCELING) LinearProgressIndicator(Modifier.fillMaxWidth())
                  Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                when(page) {
                    0 -> {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(12.dp)) { PickupPolicy.anchors.forEach{a->FilterChip(enabled=m.editable,selected=m.anchor==a,onClick={m.chooseAnchor(a)},label={Text(a.name)})}; OutlinedButton(enabled=m.editable,onClick=onVoice){Text("거점·번호 음성 입력")} }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(12.dp)) { FilterChip(enabled=m.editable,selected=m.demo,onClick={m.chooseDataMode(!m.demo)},label={Text(if(m.demo) "예시 데이터 ON" else "사용자 확인 데이터")}); TextButton(enabled=m.editable,onClick={edit=true}){Text("좌표 직접 등록")} }
                        Text("추천 스팟 · 접근 방향과 대기 여유, 도보 시간을 함께 비교합니다.",style=MaterialTheme.typography.bodyMedium)
                        Text("CCTV·버스차로·교차로·도보 3분 초과 후보 제외. 실제 진입 경로는 내비에서 확인하세요.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(m.ranked.isEmpty()) Panel("추천 후보 없음","사용자 확인 모드에서는 규제와 도보 정보를 확인한 장소를 직접 등록하세요.")
                        m.ranked.forEachIndexed{i,r->Card(onClick={m.select(r.spot.id)},enabled=m.editable,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),
                            colors=CardDefaults.cardColors(containerColor=if(m.selected?.spot?.id==r.spot.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) { Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text((if(m.selected?.spot?.id==r.spot.id) "● " else "○ ")+(i+1)+"순위 · "+r.spot.name,style=MaterialTheme.typography.titleLarge)
                            Text(r.tags.joinToString("  ")+" · "+r.score+"점 · 도보 "+r.spot.walkSec/60+"분")
                            Text("접근 "+r.access+" / 대기 "+r.waiting+" / 도보 "+r.walking+" · "+(if(r.spot.demo) "예시값" else "사용자 확인값"),style=MaterialTheme.typography.bodySmall)
                        } } }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(12.dp)) { Button(enabled=m.editable && m.navReady && m.selected!=null,onClick={m.request(false);page=1}){Text("선택 스팟으로 경로 요청")}; OutlinedButton(onClick={m.brief()}){Text("1순위 음성 안내")} }
                        OutlinedTextField(value=recipient,onValueChange={recipient=it},enabled=m.editable,label={Text("동승자 이름 (선택)")},singleLine=true)
                        Button(enabled=m.editable && m.selected!=null && m.plan.size<3,onClick={m.addPerson(recipient);recipient=""}){Text("릴레이 픽업 목록에 추가 ("+m.plan.size+"/3)")}
                    }
                    1 -> {
                        Panel(m.stage.label,m.message)
                        val i=m.next; val person=m.activePerson; val timing=i?.let{m.timing(it)}
                        if(person!=null) {
                            Panel(person.name+" · "+person.spot.name,if(timing==null) "이 동승자의 최신 ETA 수신 대기 · 외출 시각 계산 보류" else "차량 도착 "+m.time(timing.arrivalAt)+"\n권장 외출 "+(if(timing.leaveNow) "지금 출발" else m.time(timing.leaveAt))+"\n도보 "+person.spot.walkSec/60+"분 + 여유 30초")
                            Text(person.spot.walkGuide)
                            Text(m.updateNotice)
                            Text("내비 남은 시간에 따라 자동 재계산합니다. 공유된 외부 메시지는 자동 갱신되지 않으므로 변경 시 다시 공유하세요.",style=MaterialTheme.typography.bodySmall)
                            Button(enabled=m.parked && timing!=null,onClick={m.shareText()?.let{text->runCatching{context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text),"픽업 안내 카드 공유"))}.onFailure{m.notice("공유 앱을 열 수 없습니다.")}}}){Text("픽업 안내 카드 생성·공유")}
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(12.dp)) { Button(enabled=m.navReady,onClick={m.showNavigation()}){Text("Pleos 내비 화면")}; OutlinedButton(enabled=m.busy && m.navReady,onClick={m.cancel()}){Text("경로 요청 종료")}; TextButton(onClick={m.refresh()}){Text("응답 재확인")} }
                    }
                    2 -> {
                        Text("최대 3명 · 앞의 두 곳은 경유지, 마지막은 최종 목적지입니다.")
                        val people=if(m.busy) m.session else m.plan
                        people.forEachIndexed{i,p->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                            Text((i+1).toString()+". "+p.name+" · "+p.spot.name,style=MaterialTheme.typography.titleMedium)
                            Text(if(!m.busy) "경로 등록 대기" else if(i in m.boarded) "탑승 완료" else if(i==m.next) "현재 안내 활성" else "앞선 동승자 탑승 확인 후 안내 활성")
                            val timing=m.timing(i)
                            if(m.busy && i==m.next) Text(timing?.let{"도착 "+m.time(it.arrivalAt)+" · 외출 "+if(it.leaveNow) "지금" else m.time(it.leaveAt)} ?: "경유지 ETA 대기")
                            if(!m.busy) Row { TextButton(enabled=m.editable && i<people.lastIndex,onClick={m.move(i)}){Text("아래로")}; TextButton(enabled=m.editable,onClick={m.remove(i)}){Text("삭제")} }
                        }}}
                        Button(enabled=m.editable && m.navReady && m.plan.isNotEmpty(),onClick={m.request(true);page=1}){Text("이 순서로 통합 경로 요청")}
                        Button(enabled=m.canBoard && m.freshRoute,onClick={m.board()}){Text("현재 동승자 탑승 완료 → 다음 안내")}
                        Text("도착은 내비 이벤트, 탑승은 운전자 확인입니다. 다음 동승자의 시각은 해당 경유지 ETA로만 계산합니다.")
                    }
                    3 -> {
                        Panel(m.navStatus,m.location)
                        Panel("안전 인터락",m.carStatus+"\n유효한 속도·기어 응답 수신 후 6초 이내 / P 기어 / |속도|≤0.01 m/s 조건")
                        Panel("경로 정합성",if(m.freshRoute) "최종 목적지 좌표 및 경유지 이름·순서 확인 · ETA 갱신 중" else "경로 검증 또는 최신 응답 필요 · ETA 표시 중단")
                        Panel("음성 지원",m.speechStatus+"\n한국어 기기 TTS와 온디바이스 인식기 사용. 지원 엔진이 없으면 버튼으로 이용합니다. Pleos 음성 SDK 연동으로 표시하지 않습니다.")
                        TextButton(onClick={m.reconnect()}){Text("차량·내비 서비스 재연결")}
                        m.events.forEach{Text(it,style=MaterialTheme.typography.bodySmall)}
                    }
                }
                if(page!=1) Panel("안내",m.message)
                  }
                }
              }
            }
        }
    } }
    if(edit && m.editable) ManualSpotDialog(onDismiss={edit=false},onSave={n,a,b,w,g,q,c->m.saveManual(n,a,b,w,g,q,c).also{if(it) edit=false}})
    LaunchedEffect(m.parked){if(!m.parked) edit=false}
}

@Composable
private fun VehicleStatus(status: String) {
    Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(12.dp)) {
        Text(status,Modifier.padding(horizontal=20.dp,vertical=14.dp),style=MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun Panel(title: String,detail: String) {
    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(title,style=MaterialTheme.typography.titleLarge)
            Text(detail,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ManualSpotDialog(onDismiss: ()->Unit,onSave: (String,String,String,String,String,Boolean,Boolean)->Boolean) {
    var name by rememberSaveable{mutableStateOf("")}; var lat by rememberSaveable{mutableStateOf("")}; var lon by rememberSaveable{mutableStateOf("")}
    var walk by rememberSaveable{mutableStateOf("2")}; var guide by rememberSaveable{mutableStateOf("")}; var wait by rememberSaveable{mutableStateOf(false)}; var checked by rememberSaveable{mutableStateOf(false)}; var error by rememberSaveable{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,containerColor=MaterialTheme.colorScheme.surfaceVariant,title={Text("현장 확인한 승차 좌표 등록")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        OutlinedTextField(name,{name=it},modifier=Modifier.fillMaxWidth(),label={Text("스팟 이름")},singleLine=true)
        OutlinedTextField(lat,{lat=it},modifier=Modifier.fillMaxWidth(),label={Text("위도 -90~90")},singleLine=true)
        OutlinedTextField(lon,{lon=it},modifier=Modifier.fillMaxWidth(),label={Text("경도 -180~180")},singleLine=true)
        OutlinedTextField(walk,{walk=it},modifier=Modifier.fillMaxWidth(),label={Text("확인한 도보 시간 1~3분")},singleLine=true)
        OutlinedTextField(guide,{guide=it},modifier=Modifier.fillMaxWidth(),label={Text("출구·보행 경로 안내")})
        Row{Checkbox(wait,{wait=it}); Text("대기 여유 구역 확인")}
        Row{Checkbox(checked,{checked=it}); Text("CCTV·버스차로·교차로 제외 및 정차 가능 여부를 직접 확인했습니다.")}
        Text("사용자 확인값입니다. 공공기관 규제 검증 데이터로 표시되지 않습니다.",style=MaterialTheme.typography.bodySmall)
        if(error) Text("이름·좌표·도보시간·안내문과 현장 확인 체크를 검토하세요.")
    }},confirmButton={TextButton(onClick={error=!onSave(name,lat,lon,walk,guide,wait,checked)}){Text("등록")}},dismissButton={TextButton(onClick=onDismiss){Text("취소")}})
}
