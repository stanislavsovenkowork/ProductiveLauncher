package dev.stanislav.focusdeck.data.sync

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

internal data class MetricSample(val id:String,val fingerprint:String,val category:String?,val occurred:Long,
    val start:Long?,val end:Long?,val focus:Double?,val evidence:String)
data class CrossDeviceReview(val day:String="",val timezone:String="Europe/Minsk",val workTimerSeconds:Double?=null,
    val studyTimerSeconds:Double?=null,val ambiguousSeconds:Double=0.0,val futureRecords:Int=0,
    val macEvents:Int?=null,val remoteState:String="unknown",val message:String="Mac ещё не получен; часы неизвестны")

internal object CrossDeviceMath {
    fun review(samples:List<MetricSample>,now:Instant,zone:ZoneId=ZoneId.of("Europe/Minsk")):CrossDeviceReview {
        val date=LocalDate.ofInstant(now,zone)
        val start=date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end=date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val seen=samples.groupBy { it.id }.values.filter { group -> group.map { it.fingerprint }.distinct().size==1 }.map { it.first() }
        val future=seen.filter { it.occurred>now.toEpochMilli() || (it.start ?: Long.MIN_VALUE)>now.toEpochMilli() || (it.end ?: Long.MIN_VALUE)>now.toEpochMilli() }
        val safe=seen-future.toSet()
        val totals=mutableMapOf<String,Double?>("work" to null,"study" to null)
        for(row in safe)if(row.category in totals && row.occurred in start until end && row.focus==0.0 && row.evidence=="timer")totals[row.category!!]=0.0
        val intervals=safe.mapNotNull { row ->
            val a=row.start;val b=row.end;val focus=row.focus
            if(row.evidence!="timer" || a==null || b==null || focus==null || b<a || abs((b-a)/1000.0-focus)>(if(focus==0.0)0.0 else 1.0))null
            else Triple(maxOf(start,a),minOf(end,b),row.category).takeIf { it.first<it.second }
        }
        val points=intervals.flatMap { listOf(it.first,it.second) }.distinct().sorted()
        var ambiguous=0.0
        for((a,b) in points.zipWithNext()) {
            val categories=intervals.filter { it.first<=a && it.second>=b }.map { it.third }.toSet()
            if(categories.isEmpty())continue
            if(categories.size!=1 || categories.first() !in totals)ambiguous+=(b-a)/1000.0
            else {val category=categories.first()!!;totals[category]=(totals[category] ?: 0.0)+(b-a)/1000.0}
        }
        return CrossDeviceReview(date.toString(),zone.id,totals["work"],totals["study"],ambiguous,future.size)
    }
}
