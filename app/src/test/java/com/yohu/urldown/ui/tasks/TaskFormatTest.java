package com.yohu.urldown.ui.tasks;

import static org.junit.Assert.assertEquals;

import com.yohu.urldown.R;
import com.yohu.urldown.ui.widget.PlatformMark;
import com.yohu.urldown.util.Bytes;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Calendar;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class TaskFormatTest {

    @Test
    public void bytes() {
        assertEquals("512 B", Bytes.size(512));
        assertEquals("12 KB", Bytes.size(12 * 1024 + 100));
        assertEquals("3.4 MB", Bytes.size(3_565_158));
        assertEquals("1.25 GB", Bytes.size(1_342_177_280L));
    }

    @Test
    public void bytesPairSharesUnit() {
        assertEquals("3.2 / 7.4 MB", Bytes.pair(3_355_443, 7_759_462));
        assertEquals("900 KB / 7.4 MB", Bytes.pair(921_600, 7_759_462));
    }

    @Test
    public void daysBetweenUsesCalendarDays() {
        Calendar c = Calendar.getInstance();
        c.set(2025, Calendar.MARCH, 2, 0, 5, 0);
        long justAfterMidnight = c.getTimeInMillis();
        c.set(2025, Calendar.MARCH, 1, 23, 55, 0);
        long justBefore = c.getTimeInMillis();
        // 只差 10 分钟，但跨了零点，就是“昨天”
        assertEquals(1, TaskFormat.daysBetween(justBefore, justAfterMidnight));
        assertEquals(0, TaskFormat.daysBetween(justAfterMidnight, justAfterMidnight + 3_600_000L));
    }

    @Test
    public void platformMarkFallsBackToDisplayName() {
        assertEquals(R.drawable.ic_platform_douyin, PlatformMark.iconOf("douyin", null));
        // 旧任务没存平台 ID，只有展示名
        assertEquals(R.drawable.ic_platform_bilibili, PlatformMark.iconOf(null, "哔哩哔哩"));
        assertEquals(R.drawable.ic_platform_unknown, PlatformMark.iconOf(null, "某网站"));
    }
}
