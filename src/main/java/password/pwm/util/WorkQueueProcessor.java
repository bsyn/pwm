/*
 * Password Management Servlets (PWM)
 * http://www.pwm-project.org
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2016 The PWM Project
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package password.pwm.util;

import com.google.gson.annotations.SerializedName;
import password.pwm.PwmApplication;
import password.pwm.PwmConstants;
import password.pwm.error.ErrorInformation;
import password.pwm.error.PwmError;
import password.pwm.error.PwmOperationalException;
import password.pwm.util.logging.PwmLogger;
import password.pwm.util.secure.PwmRandom;

import java.io.Serializable;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * A work item queue manager.   Items submitted to the queue will eventually be worked on by the client side @code {@link ItemProcessor}.
 * @param <W>
 */
public class WorkQueueProcessor<W extends Serializable> {

    private static final TimeDuration SUBMIT_QUEUE_FULL_RETRY_CYCLE_INTERVAL = new TimeDuration(100, TimeUnit.MILLISECONDS);
    private static final TimeDuration CLOSE_RETRY_CYCLE_INTERVAL = new TimeDuration(100, TimeUnit.MILLISECONDS);

    private final Deque<String> queue;
    private final Settings settings;
    private final ItemProcessor<W> itemProcessor;

    private final PwmLogger logger;

    private volatile WorkerThread workerThread;

    private IDGenerator idGenerator = new IDGenerator();
    private Date eldestItem = null;

    public enum ProcessResult {
        SUCCESS,
        FAILED,
        RETRY,
        NOOP,
    }

    private static class IDGenerator {
        private int currentID;

        IDGenerator() {
            currentID = PwmRandom.getInstance().nextInt();
        }

        synchronized String nextID() {
            currentID += 1;
            return Integer.toString(Math.abs(currentID), 36);
        }
    }

    public WorkQueueProcessor(
            final PwmApplication pwmApplication,
            final Deque<String> queue,
            final Settings settings,
            final ItemProcessor<W> itemProcessor,
            final Class sourceClass
    ) {
        this.settings = JsonUtil.cloneUsingJson(settings, Settings.class);
        this.queue = queue;
        this.itemProcessor = itemProcessor;
        this.logger = PwmLogger.getLogger(sourceClass.getName() + "_" + this.getClass().getSimpleName());

        if (!queue.isEmpty()) {
            logger.debug("opening with " + queue.size() + " items in work queue");
        }
        logger.trace("initializing worker thread with settings " + JsonUtil.serialize(settings));

        this.workerThread = new WorkerThread();
        workerThread.setDaemon(true);
        workerThread.setName(Helper.makeThreadName(pwmApplication, sourceClass) + "-worker-");
        workerThread.start();
    }

    public void close() {
        if (workerThread == null) {
            return;
        }
        final WorkerThread localWorkerThread = workerThread;
        workerThread = null;

        localWorkerThread.flushQueueAndClose();
        final Date shutdownStartTime = new Date();

        if (queueSize() > 0) {
            logger.debug("attempting to flush queue prior to shutdown, items in queue=" + queueSize());
        }
        while (localWorkerThread.isRunning() && TimeDuration.fromCurrent(shutdownStartTime).isLongerThan(settings.getMaxShutdownWaitTime())) {
            Helper.pause(CLOSE_RETRY_CYCLE_INTERVAL.getTotalMilliseconds());
        }

        if (!queue.isEmpty()) {
            logger.warn("shutting down with " + queue.size() + " items remaining in work queue");
        }
    }

    public synchronized void submit(final W workItem) throws PwmOperationalException {
        if (workerThread == null) {
            final String errorMsg = this.getClass().getName() + " has been closed, unable to submit new item";
            throw new PwmOperationalException(new ErrorInformation(PwmError.ERROR_UNKNOWN, errorMsg));
        }

        final ItemWrapper<W> itemWrapper = new ItemWrapper<>(new Date(), workItem, idGenerator.nextID());
        final String asString = JsonUtil.serialize(itemWrapper);

        if (settings.getMaxEvents() > 0) {
            final Date startTime = new Date();
            while (!queue.offerLast(asString)) {
                if (TimeDuration.fromCurrent(startTime).isLongerThan(settings.getMaxSubmitWaitTime())) {
                    final String errorMsg = "unable to submit item to worker queue after " + settings.getMaxSubmitWaitTime().asCompactString()
                            + ", item=" + itemProcessor.convertToDebugString(workItem);
                    throw new PwmOperationalException(new ErrorInformation(PwmError.ERROR_UNKNOWN, errorMsg));
                }
                Helper.pause(SUBMIT_QUEUE_FULL_RETRY_CYCLE_INTERVAL.getTotalMilliseconds());
            }

            eldestItem = itemWrapper.getDate();
            workerThread.notifyWorkPending();

            logger.trace("item submitted: " + makeDebugText(itemWrapper));
        }
    }

    public int queueSize() {
        return queue.size();
    }

    public Date eldestItem() {
        return eldestItem;
    }

    private String makeDebugText(final ItemWrapper<W> itemWrapper) throws PwmOperationalException {
        final int itemsInQueue = WorkQueueProcessor.this.queueSize();
        String traceMsg = "[" + itemWrapper.toDebugString(itemProcessor) + "]";
        if (itemsInQueue > 0) {
            traceMsg += ", " + itemsInQueue + " items in queue";
        }
        return traceMsg;
    }

    private class WorkerThread extends Thread {

        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicBoolean shutdownFlag = new AtomicBoolean(false);
        private final AtomicBoolean notifyWorkFlag = new AtomicBoolean(true);

        private Date retryWakeupTime;

        @Override
        public void run() {
            running.set(true);
            try {
                while (!shutdownFlag.get()) {
                    processNextItem();
                    waitForWork();
                }
            } catch (Throwable t) {
                logger.error("unexpected error processing work item queue: " + Helper.readHostileExceptionMessage(t), t);
            }

            logger.trace("worker thread beginning shutdown...");

            if (!queue.isEmpty()) {
                logger.trace("processing remaining " + queue.size() + " items");

                try {
                    final Date shutdownStartTime = new Date();
                    while (retryWakeupTime == null && !queue.isEmpty() && TimeDuration.fromCurrent(shutdownStartTime).isLongerThan(settings.getMaxShutdownWaitTime())) {
                        processNextItem();
                    }
                } catch (Throwable t) {
                    logger.error("unexpected error processing work item queue: " + Helper.readHostileExceptionMessage(t), t);
                }
            }

            logger.trace("thread exiting...");
            running.set(false);
        }

        void flushQueueAndClose() {
            shutdownFlag.set(true);
            logger.trace("shutdown flag set");
            notifyWorkPending();
        }

        void notifyWorkPending() {
            notifyWorkFlag.set(true);
            LockSupport.unpark(this);
        }

        private void waitForWork() {
            if (!shutdownFlag.get()) {
                if (retryWakeupTime != null) {
                    while (retryWakeupTime.after(new Date()) && !shutdownFlag.get()) {
                        LockSupport.parkUntil(this, retryWakeupTime.getTime());
                    }
                    retryWakeupTime = null;
                } else {
                    if (queue.isEmpty() && !notifyWorkFlag.get()) {
                        eldestItem = null;
                        LockSupport.park(this);
                    }
                }
            }

            notifyWorkFlag.set(false);
        }

        public boolean isRunning() {
            return running.get();
        }

        void processNextItem() {
            final String nextStrValue = queue.peekFirst();
            if (nextStrValue == null) {
                return;
            }

            final ItemWrapper<W> itemWrapper;
            try {
                itemWrapper = JsonUtil.<ItemWrapper<W>>deserialize(nextStrValue, ItemWrapper.class);
                if (TimeDuration.fromCurrent(itemWrapper.getDate()).isLongerThan(settings.getRetryDiscardAge())) {
                    logger.warn("discarding queued item due to age, item=" + makeDebugText(itemWrapper));
                    removeQueueTop();
                    return;
                }
            } catch (Throwable e) {
                logger.warn("discarding stored record due to parsing error: " + e.getMessage() + ", record=" + nextStrValue);
                removeQueueTop();
                return;
            }

            final ProcessResult processResult;
            try {
                processResult = itemProcessor.process(itemWrapper.getWorkItem());
                if (processResult == null) {
                    logger.warn("itemProcessor.process() returned null, removing; item=" + makeDebugText(itemWrapper));
                    removeQueueTop();
                } else {
                    switch (processResult) {
                        case FAILED: {
                            logger.error("discarding item after process failure, item=" + makeDebugText(itemWrapper));
                            removeQueueTop();
                        }
                        break;

                        case RETRY: {
                            retryWakeupTime = new Date(System.currentTimeMillis() + settings.getRetryInterval().getTotalMilliseconds());
                            logger.debug("will retry item after failure, item=" + makeDebugText(itemWrapper));
                        }
                        break;

                        case SUCCESS: {
                            logger.trace("successfully processed item=" + makeDebugText(itemWrapper));
                            removeQueueTop();
                        }
                        break;

                        case NOOP:
                            break;


                        default:
                            throw new IllegalStateException("unexpected processResult type " + processResult);
                    }
                }
            } catch(Throwable e) {
                logger.error("unexpected error while processing work queue: " + e.getMessage());
                removeQueueTop();
            }

        }

        private void removeQueueTop() {
            queue.removeFirst();
            retryWakeupTime = null;
        }
    }

    private static class ItemWrapper<W extends Serializable> implements Serializable {
        @SerializedName("t")
        private final Date timestamp;

        @SerializedName("m")
        private final String item;

        @SerializedName("c")
        private final String className;

        @SerializedName("i")
        private final String id;

        ItemWrapper(final Date submitDate, final W workItem, final String itemId) {
            this.timestamp = submitDate;
            this.item = JsonUtil.serialize(workItem);
            this.className = workItem.getClass().getName();
            this.id = itemId;
        }

        Date getDate() {
            return timestamp;
        }

        W getWorkItem() throws PwmOperationalException {
            try {
                final Class clazz = Class.forName(className);
                final Object o = JsonUtil.deserialize(item, clazz);
                return (W)o;
            } catch (Exception e) {
                throw new PwmOperationalException(new ErrorInformation(PwmError.ERROR_UNKNOWN, "unexpected error deserializing work queue item: " + e.getMessage()));
            }
        }

        String getId() {
            return id;
        }

        String toDebugString(final ItemProcessor<W> itemProcessor) throws PwmOperationalException {
            final Map<String,String> debugOutput = new LinkedHashMap<>();
            debugOutput.put("date", PwmConstants.DEFAULT_DATETIME_FORMAT.format(getDate()));
            debugOutput.put("id", getId());
            debugOutput.put("item", itemProcessor.convertToDebugString(getWorkItem()));
            return StringUtil.mapToString(debugOutput,"=",",");
        }
    }

    /**
     * Implementation of {@link ItemProcessor} must be included with the construction of a {@link WorkQueueProcessor}.
     * @param <W>
     */
    public interface ItemProcessor<W extends Serializable> {
        ProcessResult process(W workItem);

        String convertToDebugString(W workItem);
    }

    public static class Settings implements Serializable {
        private int maxEvents = 1000;
        private TimeDuration maxSubmitWaitTime = new TimeDuration(5, TimeUnit.SECONDS);
        private TimeDuration retryInterval = new TimeDuration(30, TimeUnit.SECONDS);
        private TimeDuration retryDiscardAge = new TimeDuration(1, TimeUnit.HOURS);
        private TimeDuration maxShutdownWaitTime = new TimeDuration(30, TimeUnit.SECONDS);

        public int getMaxEvents() {
            return maxEvents;
        }

        public void setMaxEvents(final int maxEvents) {
            this.maxEvents = maxEvents;
        }

        public TimeDuration getMaxSubmitWaitTime() {
            return maxSubmitWaitTime;
        }

        public void setMaxSubmitWaitTime(final TimeDuration maxSubmitWaitTime) {
            this.maxSubmitWaitTime = maxSubmitWaitTime;
        }

        public TimeDuration getRetryInterval() {
            return retryInterval;
        }

        public void setRetryInterval(final TimeDuration retryInterval) {
            this.retryInterval = retryInterval;
        }

        public TimeDuration getRetryDiscardAge() {
            return retryDiscardAge;
        }

        public void setRetryDiscardAge(final TimeDuration retryDiscardAge) {
            this.retryDiscardAge = retryDiscardAge;
        }

        public TimeDuration getMaxShutdownWaitTime() {
            return maxShutdownWaitTime;
        }

        public void setMaxShutdownWaitTime(final TimeDuration maxShutdownWaitTime) {
            this.maxShutdownWaitTime = maxShutdownWaitTime;
        }
    }
}
