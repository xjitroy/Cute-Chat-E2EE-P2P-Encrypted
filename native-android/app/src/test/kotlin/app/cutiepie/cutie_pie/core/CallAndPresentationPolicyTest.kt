package app.cutiepie.cutie_pie.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class CallAndPresentationPolicyTest {
    @Test fun ringBudgetNeverExtendsRemoteExpiryAndAnswerIsNotMissed() {
        assertEquals(60_000L,CallTimingPolicy.ringBudget(200_000,100_000))
        assertEquals(800L,CallTimingPolicy.ringBudget(100_800,100_000))
        assertEquals(0L,CallTimingPolicy.ringBudget(99_000,100_000))
        assertEquals("missed",CallTimingPolicy.timeout(true,"incoming"))
        assertEquals("failed",CallTimingPolicy.timeout(true,"connecting"))
        assertEquals("unanswered",CallTimingPolicy.timeout(false,"calling"))
        assertFalse(CallTimingPolicy.ringback("calling",false))
        assertTrue(CallTimingPolicy.ringback("calling",true))
        assertFalse(CallTimingPolicy.ringback("connected",true))
    }
    @Test fun OnlyActualUnlockedConversationSuppressesAlerts() {
        assertFalse(AlertPolicy.shouldAlert(true,true,"alice","alice"))
        assertTrue(AlertPolicy.shouldAlert(true,false,"alice","alice"))
        assertTrue(AlertPolicy.shouldAlert(false,true,"alice","alice"))
        assertTrue(AlertPolicy.shouldAlert(true,true,"bob","alice"))
        assertTrue(AlertPolicy.shouldAlert(true,true,null,"alice"))
    }
    @Test fun photoTimelineUsesLocalCalendarDaysAndExcludesOtherFileKinds() {
        val zone=TimeZone.getTimeZone("America/New_York")
        fun at(day:Int,hour:Int)=Calendar.getInstance(zone).run { clear();set(2026,Calendar.MARCH,day,hour,30);timeInMillis }
        fun media(id:String,mime:String,time:Long)=PrivateLibrary.Media(id,"peer",false,id,mime,5,time)
        val groups=GalleryTimeline.groups(listOf(media("late","image/jpeg",at(8,23)),media("early","video/mp4",at(8,3)),
            media("previous","image/webp",at(7,23)),media("audio","audio/mp4",at(8,5)),media("pdf","application/pdf",at(8,6))),zone)
        assertEquals(2,groups.size)
        assertEquals(listOf("late","early"),groups.values.first().map { it.id })
        assertEquals(listOf("previous"),groups.values.last().map { it.id })
    }
}
