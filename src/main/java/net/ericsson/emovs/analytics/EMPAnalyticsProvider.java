package net.ericsson.emovs.analytics;

import android.content.Context;
import android.provider.Settings;

import com.ebs.android.exposure.auth.DeviceInfo;
import com.ebs.android.exposure.auth.EMPAuthProvider;
import com.ebs.android.exposure.auth.EMPAuthProviderWithStorage;
import com.ebs.android.exposure.clients.exposure.ExposureClient;
import com.ebs.android.exposure.clients.exposure.ExposureError;
import com.ebs.android.exposure.interfaces.IEntitlementCallback;
import com.ebs.android.exposure.interfaces.IExposureCallback;
import com.ebs.android.utilities.CheckRoot;
import com.ebs.android.utilities.RunnableThread;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by Joao Coelho on 2017-10-02.
 */

public class EMPAnalyticsProvider {
    final int CYCLE_TIME = 1000;
    final int EVENT_PURGE_TIME_DEFAULT = 3 * CYCLE_TIME;
    final int TIME_WITHOUT_BEAT_DEFAULT = 60 * CYCLE_TIME;
    final int DEVICE_CLOCK_CHECK_THRESHOLD = 5 * 60 * 1000;  // 5 minutes

    final String EVENTSINK_INIT_URL = "/eventsink/init";
    final String EVENTSINK_SEND_URL = "/eventsink/send";

    final String CUSTOMER = "Customer";
    final String BUSINESS_UNIT = "BusinessUnit";
    final String SESSION_ID = "SessionId";
    final String OFFSET_TIME = "OffsetTime";
    final String PAYLOAD = "Payload";
    final String CLOCK_OFFSET = "ClockOffset";
    final String DISPATCH_TIME = "DispatchTime";
    final String ATTRIBUTES = "Attributes";


    final String PLAYBACK_CREATED = "Playback.Created";
    final String PLAYBACK_READY = "Playback.PlayerReady";
    final String PLAYBACK_STARTED = "Playback.Started";
    final String PLAYBACK_PAUSED = "Playback.Paused";
    final String PLAYBACK_RESUMED = "Playback.Resumed";
    final String PLAYBACK_SCRUBBED_TO = "Playback.ScrubbedTo";
    final String PLAYBACK_START_CASTING = "Playback.StartCasting";
    final String PLAYBACK_STOP_CASTING = "Playback.StopCasting";
    final String PLAYBACK_HANDSHAKE_STARTED = "Playback.HandshakeStarted";
    final String PLAYBACK_BITRATE_CHANGED = "Playback.BitrateChanged";
    final String PLAYBACK_COMPLETED = "Playback.Completed";
    final String PLAYBACK_ERROR = "Playback.Error";
    final String PLAYBACK_ABORTED = "Playback.Aborted";
    final String PLAYBACK_BUFFERING_STARTED = "Playback.BufferingStarted";
    final String PLAYBACK_BUFFERING_ENDED = "Playback.BufferingEnded";
    final String PLAYBACK_HEARTBEAT = "Playback.Heartbeat";
    final String PLAYBACK_DEVICE_INFO = "Playback.DeviceInfo";

    private Context context;
    private HashMap<String, SessionDetails> eventPool;
    private HashMap<String, String> customAttributes;

    RunnableThread cyclicChecker;
    long serviceCurrentTime;
    long serviceLastTime;
    boolean includeDeviceMetrics;

    private static class EMPAnalyticsProviderHolder {
        private final static EMPAnalyticsProvider sInstance = new EMPAnalyticsProvider();
    }

    public EMPAnalyticsProvider() {
        this.eventPool = new HashMap<>();
        this.customAttributes = new HashMap<>();
        init();
    }

    public static EMPAnalyticsProvider getInstance(Context context) {
        EMPAnalyticsProvider.EMPAnalyticsProviderHolder.sInstance.setApplicationContext(context);
        return EMPAnalyticsProvider.EMPAnalyticsProviderHolder.sInstance;
    }

    protected void setApplicationContext(Context applicationContext) {
        this.context = applicationContext;
    }

    private void init() {
        clear();
        serviceCurrentTime = 0;
        serviceLastTime = 0;
        this.cyclicChecker = new RunnableThread(new Runnable() {
            @Override
            public void run() {
                for(;;) {
                    try {
                        Thread.sleep(CYCLE_TIME);
                        cycle();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        return;
                    }
                }
            }
        });
        this.cyclicChecker.start();
    }

    public void clear() {
        if (this.cyclicChecker != null && this.cyclicChecker.isInterrupted() == false && this.cyclicChecker.isAlive()) {
            this.cyclicChecker.interrupt();
        }
        serviceCurrentTime = 0;
    }

    public void dispatchNow() {
        sendData();
    }

    public void exitOngoingSessions() {
        for (Map.Entry<String, SessionDetails> entry : this.eventPool.entrySet()) {
            aborted(entry.getKey(), entry.getValue().getCurrentTime(), null);
        }
    }

    public void setCustomAttribute(String k, String v) {
        this.customAttributes.put(k, v);
    }

    public void clearCustomAttributes() {
        this.customAttributes.clear();
    }

    public void created(String sessionId, HashMap<String, String> parameters) {
        EventBuilder builder = new EventBuilder(PLAYBACK_CREATED, parameters);
        addEventToPool(sessionId, builder, false);
    }

    public void ready(String sessionId, HashMap<String, String> parameters) {
        EventBuilder builder = new EventBuilder(PLAYBACK_READY, parameters);
        addEventToPool(sessionId, builder, false);
    }

    public void started(String sessionId, long currentTime, HashMap<String, String> parameters) {
        EventBuilder builder = new EventBuilder(PLAYBACK_STARTED, parameters)
                .withProp(ATTRIBUTES, this.customAttributes);
        setCurrentTime(sessionId, currentTime);
        addEventToPool(sessionId, builder, true);
        changeSessionState(sessionId, SessionDetails.SESSION_STATE_PLAYING);
    }

    public void paused(String sessionId, long currentTime, HashMap<String, String> parameters) {
        EventBuilder builder = new EventBuilder(PLAYBACK_PAUSED, parameters);
        setCurrentTime(sessionId, currentTime);
        addEventToPool(sessionId, builder, true);
    }

    public void resumed(String sessionId, long currentTime, HashMap<String, String> parameters) {
        EventBuilder builder = new EventBuilder(PLAYBACK_RESUMED, parameters);
        setCurrentTime(sessionId, currentTime);
        addEventToPool(sessionId, builder, true);
    }

    public void seeked(String sessionId, long currentTime, HashMap<String, String> parameters) {
        EventBuilder builder = new EventBuilder(PLAYBACK_SCRUBBED_TO, parameters);
        setCurrentTime(sessionId, currentTime);
        addEventToPool(sessionId, builder, true);
    }

    public void startCasting(String sessionId, long currentTime, HashMap<String, String> parameters) {
        EventBuilder builder = new EventBuilder(PLAYBACK_START_CASTING, parameters);
        setCurrentTime(sessionId, currentTime);
        addEventToPool(sessionId, builder, true);
        changeSessionState(sessionId, SessionDetails.SESSION_STATE_DIRTY);
    }

    public void stopCasting(String sessionId, long currentTime, HashMap<String, String> parameters) {
        EventBuilder builder = new EventBuilder(PLAYBACK_STOP_CASTING, parameters);
        setCurrentTime(sessionId, currentTime);
        addEventToPool(sessionId, builder, true);
    }

    public void handshakeStarted(String sessionId, HashMap<String, String> parameters) {
        EventBuilder builder = new EventBuilder(PLAYBACK_HANDSHAKE_STARTED, parameters);
        addEventToPool(sessionId, builder, false);
    }

    public void bitrateChanged(String sessionId, long currentTime, HashMap<String, String> parameters) {
        EventBuilder builder = new EventBuilder(PLAYBACK_BITRATE_CHANGED, parameters);
        setCurrentTime(sessionId, currentTime);
        addEventToPool(sessionId, builder, true);
    }

    public void completed(String sessionId, HashMap<String, String> parameters) {
        EventBuilder builder = new EventBuilder(PLAYBACK_COMPLETED, parameters);
        addEventToPool(sessionId, builder, false);
        changeSessionState(sessionId, SessionDetails.SESSION_STATE_FINISHED);
    }

    public void error(String sessionId, long currentTime, HashMap<String, String> parameters) {
        EventBuilder builder = new EventBuilder(PLAYBACK_ERROR, parameters);
        setCurrentTime(sessionId, currentTime);
        addEventToPool(sessionId, builder, true);
        changeSessionState(sessionId, SessionDetails.SESSION_STATE_DIRTY);
    }

    public void aborted(String sessionId, long currentTime, HashMap<String, String> parameters) {
        EventBuilder builder = new EventBuilder(PLAYBACK_ABORTED, parameters);
        setCurrentTime(sessionId, currentTime);
        addEventToPool(sessionId, builder, true);
        changeSessionState(sessionId, SessionDetails.SESSION_STATE_FINISHED);
    }

    public void waitingStarted(String sessionId, long currentTime, HashMap<String, String> parameters) {
        EventBuilder builder = new EventBuilder(PLAYBACK_BUFFERING_STARTED, parameters);
        setCurrentTime(sessionId, currentTime);
        addEventToPool(sessionId, builder, true);
    }

    public void waitingEnded(String sessionId, long currentTime, HashMap<String, String> parameters) {
        EventBuilder builder = new EventBuilder(PLAYBACK_BUFFERING_ENDED, parameters);
        setCurrentTime(sessionId, currentTime);
        addEventToPool(sessionId, builder, true);
    }

    public void setCurrentTime(String sessionId, long currentTime) {
        if (eventPool.containsKey(sessionId) == false) {
            return;
        }
        eventPool.get(sessionId).setCurrentTime(currentTime);
    }

    private synchronized void sendData() {
        for (Map.Entry<String, SessionDetails> entry : this.eventPool.entrySet()) {
            final String sessionId = entry.getKey();
            final SessionDetails details = entry.getValue();
            if (details == null) {
                continue;
            }
            if (details.getCurrentState() == SessionDetails.SESSION_STATE_PLAYING && details.getEvents().length() == 0) {
                addEventToPool(sessionId, new EventBuilder(PLAYBACK_HEARTBEAT), true);
            }
            if (details.getCurrentState() != SessionDetails.SESSION_STATE_IDLE && details.getCurrentState() != SessionDetails.SESSION_STATE_REMOVED) {
                if (details.getEvents().length() == 0) {
                    if (details.getCurrentState() != SessionDetails.SESSION_STATE_FINISHED) {
                        removeSession(sessionId, false);
                    }
                    continue;
                }

                try {
                    // TODO: retry mechanism

                    if (Math.abs(this.serviceCurrentTime - this.serviceLastTime) > DEVICE_CLOCK_CHECK_THRESHOLD) {
                        sinkInit(sessionId);
                    }

                    JSONObject payload = new JSONObject();

                    payload.put(SESSION_ID, sessionId);
                    payload.put(DISPATCH_TIME, System.currentTimeMillis());
                    payload.put(PAYLOAD, details.getEvents());
                    payload.put(CLOCK_OFFSET, details.getClockOffset());

                    sinkSend(payload, new Runnable() {
                        @Override
                        public void run() {
                            clearSessionEvents(sessionId);
                            if (details.getCurrentState() == SessionDetails.SESSION_STATE_FINISHED) {
                                removeSession(sessionId, false);
                            }
                        }
                    }, null);
                }
                catch (JSONException e) {
                    e.printStackTrace();
                    continue;
                }
            }
        }
    }

    private void includeDeviceMetrics(boolean include) {
        this.includeDeviceMetrics = include;
    }

    private void clearSessionEvents(String sessionId) {
        if (this.eventPool.containsKey(sessionId)) {
            this.eventPool.get(sessionId).clearEvents();
            this.eventPool.get(sessionId).setCurrentRetries(0);
        }
    }

    private void cycle() {
        serviceCurrentTime += CYCLE_TIME;
        if (hasData()) {
            if (serviceLastTime + EVENT_PURGE_TIME_DEFAULT < serviceCurrentTime) {
                sendData();
                serviceLastTime = serviceCurrentTime;
            }
        }
        else {
            if (serviceLastTime + TIME_WITHOUT_BEAT_DEFAULT < serviceCurrentTime) {
                sendData();
                serviceLastTime = serviceCurrentTime;
            }
        }
    }

    private boolean hasData() {
        for (SessionDetails details : eventPool.values()) {
            if (details.getEvents().length() > 0) {
                return true;
            }
        }
        return false;
    }

    private void removeSession(String sessionId, boolean removeFromMemory) {
        if (removeFromMemory) {
            eventPool.remove(sessionId);
        }
        else if (eventPool.containsKey(sessionId)) {
            eventPool.get(sessionId).setCurrentState(SessionDetails.SESSION_STATE_REMOVED);
        }
    }

    private void changeSessionState(String sessionId, String state) {
        if (eventPool.containsKey(sessionId) == false) {
            return;
        }
        eventPool.get(sessionId).setCurrentState(state);
    }

    private void addEventToPool(final String sessionId, EventBuilder eventBuilder, boolean includeOffset) {
        boolean addDeviceInfo = false;
        if (this.eventPool.containsKey(sessionId) == false) {
            this.eventPool.put(sessionId, new SessionDetails());
            this.sinkInit(sessionId);
            addDeviceInfo = true;
        }

        SessionDetails details = this.eventPool.get(sessionId);

        if (includeOffset) {
            eventBuilder.withProp(OFFSET_TIME, details.currentTime);
        }

        details.addEvent(eventBuilder.get());

        if (addDeviceInfo) {
            addEventToPool(sessionId, addDeviceInfo(), false);
        }
    }

    private EventBuilder addDeviceInfo() {
        DeviceInfo deviceInfo = DeviceInfo.getInstance(context);
        return new EventBuilder(PLAYBACK_DEVICE_INFO)
                .withProp("DeviceId", deviceInfo.getDeviceId())
                .withProp("DeviceModel", deviceInfo.getModel())
                .withProp("OS", deviceInfo.getOS())
                .withProp("OSVersion", deviceInfo.getOSVersion())
                .withProp("Manufacturer", deviceInfo.getManufacturer())
                .withProp("IsRooted", CheckRoot.isDeviceRooted());
    }

    private void calculateClockOffset(String sessionId, JSONObject initResponse, long initInitialTime) {
        if (this.eventPool.containsKey(sessionId) == false) {
            return;
        }
        long currentTime = System.currentTimeMillis();
        try {
            initResponse.getLong("repliedTime");
            long clockOfsset = (currentTime - initResponse.getLong("repliedTime") + initInitialTime - initResponse.getLong("receivedTime")) / 2;
            this.eventPool.get(sessionId).setClockOffset(clockOfsset);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void fetchIncludeDeviceMetrics(JSONObject initResponse) {
        boolean includeDeviceMetrics = false;
        if (initResponse.has("settings")) {
            try {
                includeDeviceMetrics = initResponse.getJSONObject("settings").optBoolean("includeDeviceMetrics", false);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        includeDeviceMetrics(includeDeviceMetrics);
    }

    private void sinkInit(final String sessionId) {
        ExposureClient exposureClient = ExposureClient.getInstance();
        if (exposureClient.getSessionToken() == null) {
            //listener.onError(ExposureError.NO_SESSION_TOKEN);
            // TODO: handle case where no session token
            return;
        }

        JSONObject initPayload = new JSONObject();
        try {
            initPayload.put(CUSTOMER, exposureClient.getCustomer());
            initPayload.put(BUSINESS_UNIT, exposureClient.getBusinessUnit());
            initPayload.put(SESSION_ID, sessionId);
        }
        catch (JSONException e) {
            e.printStackTrace();
        }

        // TODO: handle error
        final long initInitialTime = System.currentTimeMillis();
        ExposureClient.getInstance().postSync(EVENTSINK_INIT_URL, initPayload, new IExposureCallback() {
            @Override
            public void onCallCompleted(JSONObject response, ExposureError error) {
                if (error == null) {
                    if(response != null) {
                        calculateClockOffset(sessionId, response, initInitialTime);
                        fetchIncludeDeviceMetrics(response);
                    }
                }
            }
        });
    }

    private void sinkSend(JSONObject payload, final Runnable onSuccess, Runnable onError) {
        ExposureClient exposureClient = ExposureClient.getInstance();
        if (exposureClient.getSessionToken() == null) {
            // TODO: handle case where no session token
            return;
        }

        try {
            payload.put(CUSTOMER, exposureClient.getCustomer());
            payload.put(BUSINESS_UNIT, exposureClient.getBusinessUnit());
        }
        catch (JSONException e) {
            e.printStackTrace();
        }

        // TODO: handle error
        ExposureClient.getInstance().postSync(EVENTSINK_SEND_URL, payload, new IExposureCallback() {
            @Override
            public void onCallCompleted(JSONObject response, ExposureError error) {
                if(error == null) {
                    if(onSuccess != null) {
                        onSuccess.run();
                    }
                }
                else {
                    // TODO: implement on error
                }
            }
        });
    }



}