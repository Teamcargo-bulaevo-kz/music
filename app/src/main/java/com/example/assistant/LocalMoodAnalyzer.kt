package com.example.assistant

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.*

data class MoodAnalysis(val scores: Map<String, Int>, val energy: Int, val bpm: Int, val confidence: Int, val description: String, val valence: Int = 50, val arousal: Int = 50, val tension: Int = 50, val danceability: Int = 0)
data class AudioFeatures(val rms: Double, val centroid: Double, val low: Double, val high: Double, val flatness: Double, val flux: Double, val beat: Double, val bpm: Int, val dynamics: Double, val count: Int, val durationSeconds: Double = 0.0, val variation: Double = 0.0)

/** Локальный анализ PCM: сеть, Groq и внешние модели не используются. */
object LocalMoodAnalyzer {
    fun analyze(context: Context, uri: Uri): MoodAnalysis {
        val targetRate = 11025
        val pcm = decode(context, uri, targetRate)
        require(pcm.size >= targetRate * 3) { "Недостаточно аудио для анализа (нужно не менее 3 секунд)" }
        return classify(extractFeatures(pcm, targetRate))
    }

    private fun decode(context: Context, uri: Uri, targetRate: Int): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        // Ограничиваем память: до четырёх минут моно PCM 11,025 кГц.
        val out = FloatArray(targetRate * 240)
        var outCount = 0
        var sourceFrames = 0L
        var nextSampleFrame = 0.0
        try {
            extractor.setDataSource(context, uri, null)
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    extractor.selectTrack(i); format = f; break
                }
            }
            val input = format ?: error("Аудиодорожка не найдена")
            codec = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(input, null, null, 0); codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false
            var channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 8)
            var rate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            while (!outputDone && outCount < out.size) {
                if (!inputDone) {
                    val ix = codec.dequeueInputBuffer(10_000)
                    if (ix >= 0) {
                        val b = codec.getInputBuffer(ix)!!; b.clear()
                        if (extractor.sampleTime < 0) {
                            codec.queueInputBuffer(ix,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone=true
                        } else {
                            val size=extractor.readSampleData(b,0)
                            if(size<0){codec.queueInputBuffer(ix,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true}
                            else {codec.queueInputBuffer(ix,0,size,extractor.sampleTime,extractor.sampleFlags);extractor.advance()}
                        }
                    }
                }
                when(val ix=codec.dequeueOutputBuffer(info,10_000)){
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED->{channels=codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1,8);rate=codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)}
                    MediaCodec.INFO_TRY_AGAIN_LATER->Unit
                    else->if(ix>=0){
                        val b=codec.getOutputBuffer(ix)
                        if(b!=null&&info.size>0){
                            b.position(info.offset);b.limit(info.offset+info.size)
                            val enc=if(codec.outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))codec.outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            val isFloat=enc==AudioFormat.ENCODING_PCM_FLOAT
                            val v=b.slice().order(ByteOrder.nativeOrder())
                            val bytes=if(isFloat)4 else 2
                            val frames=info.size/bytes/channels
                            for(frame in 0 until frames){
                                var sample=0.0
                                repeat(channels){sample+=if(isFloat)v.getFloat().toDouble() else v.getShort().toDouble()/32768.0}
                                val mono=(sample/channels).toFloat().coerceIn(-1f,1f)
                                val absoluteFrame=sourceFrames+frame
                                if(absoluteFrame+1e-9>=nextSampleFrame && outCount<out.size){
                                    out[outCount++]=mono
                                    nextSampleFrame += rate.toDouble()/targetRate
                                }
                                if(outCount>=out.size)break
                            }
                            sourceFrames += frames
                        }
                        outputDone=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                        codec.releaseOutputBuffer(ix,false)
                    }
                }
            }
        } finally {
            try{codec?.stop()}catch(_:Exception){}
            try{codec?.release()}catch(_:Exception){}
            extractor.release()
        }
        return out.copyOf(outCount)
    }

    fun extractFeatures(x: FloatArray, sampleRate: Int): AudioFeatures {
        val n=1024;val hop=512
        val env=ArrayList<Double>();val centers=ArrayList<Double>();val lows=ArrayList<Double>();val highs=ArrayList<Double>();val flats=ArrayList<Double>();val fluxes=ArrayList<Double>()
        var sumSq=0.0;var prev=DoubleArray(n/2+1)
        for(v in x)sumSq+=v.toDouble()*v
        var pos=0
        while(pos+n<=x.size){
            val re=DoubleArray(n){j->x[pos+j]*(.5-.5*cos(2*PI*j/(n-1)))};val im=DoubleArray(n)
            env.add(sqrt(re.sumOf{it*it}/n))
            var j=0
            for(i in 1 until n){var bit=n shr 1;while(j and bit!=0){j=j xor bit;bit=bit shr 1};j=j xor bit;if(i<j){val t=re[i];re[i]=re[j];re[j]=t}}
            var len=2
            while(len<=n){val a=-2*PI/len;val wr0=cos(a);val wi0=sin(a);var base=0
                while(base<n){var wr=1.0;var wi=0.0
                    for(k in 0 until len/2){val e=base+k;val o=e+len/2;val tr=wr*re[o]-wi*im[o];val ti=wr*im[o]+wi*re[o];re[o]=re[e]-tr;im[o]=im[e]-ti;re[e]+=tr;im[e]+=ti;val nw=wr*wr0-wi*wi0;wi=wr*wi0+wi*wr0;wr=nw}
                    base+=len
                };len*=2
            }
            var pwr=0.0;var weighted=0.0;var low=0.0;var high=0.0;var log=0.0;var count=0;var flux=0.0
            for(bin in 1..n/2){val p=re[bin]*re[bin]+im[bin]*im[bin];val hz=bin.toDouble()*sampleRate/n;pwr+=p;weighted+=hz*p;if(hz<250)low+=p;if(hz>4000)high+=p;if(p>1e-15){log+=ln(p);count++};val mag=sqrt(p);flux+=max(0.0,mag-prev[bin]);prev[bin]=mag}
            centers.add(if(pwr>0)weighted/pwr else 0.0);lows.add(if(pwr>0)low/pwr else 0.0);highs.add(if(pwr>0)high/pwr else 0.0)
            val ar=pwr/(n/2);flats.add(if(count>0&&ar>0)exp(log/count)/ar else 0.0);fluxes.add(flux);pos+=hop
        }
        val sorted=env.sorted();val median=if(sorted.isEmpty())0.0 else sorted[sorted.size/2]
        val dynamics=if(median>1e-8)((env.maxOrNull()?:median)/median).coerceAtMost(12.0)else 1.0
        val beat=detectBeat(env,sampleRate.toDouble()/hop)
        val meanEnv=env.average().coerceAtLeast(1e-9)
        val variation=if(env.isNotEmpty())sqrt(env.map{(it-meanEnv)*(it-meanEnv)}.average())/meanEnv else 0.0
        return AudioFeatures(sqrt(sumSq/max(1,x.size)),centers.average().finiteOrZero(),lows.average().finiteOrZero(),highs.average().finiteOrZero(),flats.average().finiteOrZero(),fluxes.average().finiteOrZero(),beat.first,beat.second,dynamics,x.size,x.size.toDouble()/sampleRate,variation.finiteOrZero())
    }
    private fun Double.finiteOrZero()=if(isFinite())this else 0.0

    private fun detectBeat(env:List<Double>,fps:Double):Pair<Double,Int>{
        if(env.size<80)return 0.0 to 0
        val mean=env.average();val x=env.map{it-mean};var best=0.0;var bpm=0
        for(candidate in 60..180){val lag=(fps*60/candidate).roundToInt();var cross=0.0;var a=0.0;var b=0.0
            for(i in lag until x.size){cross+=x[i]*x[i-lag];a+=x[i]*x[i];b+=x[i-lag]*x[i-lag]}
            val corr=if(a>0&&b>0)cross/sqrt(a*b)else 0.0
            if(corr>best){best=corr;bpm=candidate}
        };return best.coerceIn(0.0,1.0) to bpm
    }

    /** Процентное распределение оценок: сумма ровно 100. Это не медицинская и не объективная вероятность. */
    fun classify(f:AudioFeatures):MoodAnalysis{
        fun u(v:Double)=if(v.isFinite())v.coerceIn(0.0,1.0)else 0.0
        val energy=.42*u((f.rms-.012)/.20)+.28*u((f.bpm-60.0)/120)+.18*u(f.flux*4)+.12*u(f.centroid/6500)
        val bright=u(f.centroid/6500);val rhythm=u((f.beat-.07)/.55);val smooth=1-u(f.flatness*2.2);val stable=1-u(f.dynamics/8)
        val dance=if(f.bpm in 100..140)1.0 else 1-min(1.0,abs(f.bpm-120)/75.0)
        // Двухосевая модель: валентность и возбуждение дополняют, но не подменяют теги настроения.
        val valence=u(.38*bright+.28*smooth+.20*energy+.14*(1-u(f.low*1.8)))
        val arousal=u(.55*energy+.25*rhythm+.20*u(f.variation/2.5))
        val tension=u(.35*f.flatness+.25*u(f.flux*3)+.22*u(f.dynamics/8)+.18*(1-smooth))
        val danceability=u(.48*dance+.32*rhythm+.20*energy)
        val raw=linkedMapOf(
            "energetic" to (.12+.45*energy+.22*rhythm+.21*u(f.flux*3)),
            "calm" to (.12+.42*(1-energy)+.24*stable+.22*(1-bright)),
            "happy" to (.12+.30*bright+.28*energy+.22*rhythm+.08*smooth),
            "sad" to (.10+.38*(1-energy)+.22*(1-bright)+.18*(1-smooth)),
            "romantic" to (.12+.30*smooth+.25*(1-energy)+.18*stable+.15*(1-f.high)),
            "dance" to (.10+.38*dance+.34*rhythm+.18*energy),
            "focus" to (.12+.34*stable+.28*(1-energy)+.18*smooth),
            "epic" to (.10+.38*energy+.24*f.dynamics.coerceAtMost(4.0)/4+.18*(1-f.low)),
            "dark" to (.10+.28*(1-bright)+.24*f.low+.22*energy+.16*f.flatness)
        )
        val top=raw.values.maxOrNull()?:1.0;val ex=raw.mapValues{kotlin.math.exp((it.value-top)/.24)};val total=ex.values.sum()
        val exact=ex.mapValues{it.value/total*100};val scores=exact.mapValues{floor(it.value).toInt()}.toMutableMap()
        exact.entries.sortedByDescending{it.value-floor(it.value)}.take(100-scores.values.sum()).forEach{scores[it.key]=scores.getValue(it.key)+1}
        // Это оценка пригодности сигнала, не вероятность правильного распознавания.
        val durationQuality=u(f.durationSeconds/90.0)
        val featureQuality=1-u(f.flatness*.65+if(f.rms<.003) .45 else 0.0)
        val confidence=(25+35*durationQuality+20*u(f.beat*1.8)+20*featureQuality).roundToInt().coerceIn(20,95)
        val level=(1+(energy*4).roundToInt()).coerceIn(1,5)
        val desc=(if(f.bpm>0)f.bpm.toString()+" BPM · " else "")+
            "позитивность "+(valence*100).roundToInt()+"% · возбуждение "+(arousal*100).roundToInt()+"% · напряжение "+(tension*100).roundToInt()+"% · танцевальность "+(danceability*100).roundToInt()+"%"
        return MoodAnalysis(scores,level,f.bpm,confidence,desc,(valence*100).roundToInt(),(arousal*100).roundToInt(),(tension*100).roundToInt(),(danceability*100).roundToInt())
    }
}
