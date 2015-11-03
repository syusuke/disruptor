package com.lmax.disruptor;

import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.dsl.SequencerFactory;
import com.lmax.disruptor.support.LongEvent;
import com.lmax.disruptor.util.DaemonThreadFactory;
import org.hamcrest.CoreMatchers;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.util.Collection;
import java.util.concurrent.locks.LockSupport;

import static java.util.Arrays.asList;

@RunWith(Parameterized.class)
public class BatchingTest
{
    private final SequencerFactory producerType;

    @SuppressWarnings("unused")
    public BatchingTest(String name, SequencerFactory producerType)
    {
        this.producerType = producerType;
    }

    @Parameters(name = "{0}")
    public static Collection<Object[]> parameters()
    {

        return asList(
            new Object[][] {
                {"waitfree", ProducerType.waitFree(4)},
                {"multi", ProducerType.MULTI},
                {"single", ProducerType.SINGLE},
            });
    }

    private static class ParallelEventHandler implements EventHandler<LongEvent>
    {
        private final long mask;
        private final long ordinal;
        private final int batchSize = 10;

        private long eventCount;
        private long batchCount;
        private long publishedValue;
        private long tempValue;
        private volatile long processed;

        public ParallelEventHandler(long mask, long ordinal)
        {
            this.mask = mask;
            this.ordinal = ordinal;
        }

        @Override
        public void onEvent(LongEvent event, long sequence, boolean endOfBatch) throws Exception
        {
            if ((sequence & mask) == ordinal)
            {
                eventCount++;
                tempValue = event.get();
            }

            if (endOfBatch || ++batchCount >= batchSize)
            {
                publishedValue = tempValue;
                batchCount = 0;
            }
            else
            {
                LockSupport.parkNanos(1);
            }

            processed = sequence;
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldBatch() throws Exception
    {
        Disruptor<LongEvent> d = new Disruptor<LongEvent>(
            LongEvent.FACTORY, 2048, DaemonThreadFactory.INSTANCE,
            producerType, new SleepingWaitStrategy());

        ParallelEventHandler handler1 = new ParallelEventHandler(1, 0);
        ParallelEventHandler handler2 = new ParallelEventHandler(1, 1);

        d.handleEventsWith(handler1, handler2);

        RingBuffer<LongEvent> buffer = d.start();

        EventTranslator<LongEvent> translator = new EventTranslator<LongEvent>()
        {
            @Override
            public void translateTo(LongEvent event, long sequence)
            {
                event.set(sequence);
            }
        };

        int eventCount = 10000;
        for (int i = 0; i < eventCount; i++)
        {
            buffer.publishEvent(translator);
        }

        while (handler1.processed != eventCount - 1 ||
            handler2.processed != eventCount - 1)
        {
            Thread.sleep(1);
        }

        Assert.assertThat(handler1.publishedValue, CoreMatchers.is((long) eventCount - 2));
        Assert.assertThat(handler1.eventCount, CoreMatchers.is((long) eventCount / 2));
        Assert.assertThat(handler2.publishedValue, CoreMatchers.is((long) eventCount - 1));
        Assert.assertThat(handler2.eventCount, CoreMatchers.is((long) eventCount / 2));
    }
}