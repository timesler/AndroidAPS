package info.nightscout.androidaps.plugins.pump.omnipod;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.DetailedBolusInfo;
import info.nightscout.androidaps.data.Intervals;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.data.ProfileIntervals;
import info.nightscout.androidaps.db.CareportalEvent;
import info.nightscout.androidaps.db.ProfileSwitch;
import info.nightscout.androidaps.db.Source;
import info.nightscout.androidaps.db.TemporaryBasal;
import info.nightscout.androidaps.interfaces.Interval;
import info.nightscout.androidaps.plugins.configBuilder.ConfigBuilderPlugin;
import info.nightscout.androidaps.plugins.configBuilder.ProfileFunctions;
import info.nightscout.androidaps.plugins.general.overview.events.EventDismissNotification;
import info.nightscout.androidaps.plugins.general.overview.events.EventNewNotification;
import info.nightscout.androidaps.plugins.general.overview.notifications.Notification;
import info.nightscout.androidaps.plugins.pump.common.utils.DateTimeUtil;
import info.nightscout.androidaps.plugins.pump.omnipod.alerts.OmniCoreAlerts;
import info.nightscout.androidaps.plugins.pump.omnipod.api.rest.OmniCoreHistoricalResult;
import info.nightscout.androidaps.plugins.pump.omnipod.api.rest.OmniCoreSetProfileRequest;
import info.nightscout.androidaps.plugins.pump.omnipod.api.rest.OmniCoreBolusRequest;
import info.nightscout.androidaps.plugins.pump.omnipod.api.rest.OmniCoreCancelBolusRequest;
import info.nightscout.androidaps.plugins.pump.omnipod.api.rest.OmniCoreCancelTempBasalRequest;
import info.nightscout.androidaps.plugins.pump.omnipod.api.rest.OmniCoreRequest;
import info.nightscout.androidaps.plugins.pump.omnipod.api.rest.OmniCoreStatusRequest;
import info.nightscout.androidaps.plugins.pump.omnipod.api.rest.OmniCoreTempBasalRequest;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.bus.RxBus;
import info.nightscout.androidaps.plugins.pump.omnipod.events.EventOmnipodUpdateGui;
import info.nightscout.androidaps.plugins.pump.omnipod.history.OmniCoreCommandHistory;
import info.nightscout.androidaps.plugins.pump.omnipod.history.OmniCoreCommandHistoryItem;
import info.nightscout.androidaps.plugins.treatments.Treatment;
import info.nightscout.androidaps.plugins.treatments.TreatmentsPlugin;
import info.nightscout.androidaps.utils.DateUtil;
import info.nightscout.androidaps.utils.SP;
import info.nightscout.androidaps.plugins.pump.omnipod.api.rest.OmniCoreResult;

public class OmnipodPdm {

    private final Context _context;

    private OmniCoreResult _lastResult;
    private Timer _omniCoreTimer;
    private boolean _connected;
    private boolean _connectionStatusKnown;
    private long _lastStatusRequest = 0;
    private long _lastStatusResponse = 0;

    private final Logger _log;

    private OmniCoreCommandHistory _commandHistory;
    private OmniCoreAlerts _alertProcessor;

    public OmnipodPdm(Context context)
    {
        _context = context;
        _log =  LoggerFactory.getLogger(L.PUMP);
        _commandHistory = new OmniCoreCommandHistory();
        _alertProcessor = new OmniCoreAlerts();
    }

    public void OnStart() {
        _lastResult = OmniCoreResult.fromJson(SP.getString(R.string.key_omnicore_last_result, null));

        if (_lastResult == null)
        {
            _lastResult = new OmniCoreResult();
            _lastResult.Success = true;
            _lastResult.BasalSchedule = new BigDecimal[48];
            for (int i = 0; i < 48; i++) {
                _lastResult.BasalSchedule[i] = new BigDecimal(0);
            }
            SP.putString(R.string.key_omnicore_last_result, _lastResult.asJson());
        }
        _lastResult.LastResultDateTime = 0;
        _connectionStatusKnown = false;
        getPodStatus();
      //  getResult(new OmniCoreStatusRequest());
    }

    public void OnStop() {
        synchronized (this) {
            _omniCoreTimer.cancel();
        }
    }

    public boolean IsInitialized() {
        return IsConnected();
    }

    public boolean IsSuspended() {
        return !_lastResult.PodRunning;
    }

    public boolean IsBusy() {
        return false;
    }

    public boolean IsConnected() {
        return _connectionStatusKnown && _connected;
    }

    public void Connect() {
    }

    public boolean IsConnecting() {
        return false;
    }

    public void StopConnecting() {
    }

    public void FinishHandshaking() { }

    public boolean IsHandshakeInProgress() {
        return false;
    }

    public void Disconnect() {}

    public synchronized OmniCoreResult getResult(OmniCoreRequest request) {

        if (_omniCoreTimer != null)
        {
            _omniCoreTimer.cancel();
            _omniCoreTimer = null;
        }
        if (L.isEnabled(L.PUMP)) {
            _log.debug("OMNICORE getResult() for request: " + request.getRequestDetails());
        }
        SP.putString(R.string.key_omnicore_last_command, request.getRequestDetails());
        SP.putString(R.string.key_omnicore_last_commandstate, "Pending");


        OmniCoreResult result = request.getRemoteResult(_lastResult.LastResultDateTime);

        if (result != null) {
            if (L.isEnabled(L.PUMP)) {
                _log.debug("OMNICORE result Returned. Result: " + result.asJson());
            }
            if (!_connected || !_connectionStatusKnown)
            {
                RxBus.INSTANCE.send(new EventDismissNotification(Notification.OMNIPY_CONNECTION_STATUS));
                Notification notification = new Notification(Notification.OMNIPY_CONNECTION_STATUS,
                        MainApp.gs(R.string.omnicore_connected), Notification.INFO, 1);
                RxBus.INSTANCE.send(new EventNewNotification(notification));
            }
            _connectionStatusKnown = true;
            _connected = true;

            if (_lastResult.LastResultDateTime == 0)
                processHistory(result, false);
            else
                processHistory(result, _lastResult.PodRunning);

            if (_lastResult.PodRunning && !result.PodRunning)
            {
                RxBus.INSTANCE.send(new EventDismissNotification(Notification.OMNIPY_POD_CHANGE));
                Notification notification = new Notification(Notification.OMNIPY_POD_CHANGE,
                        String.format(MainApp.gs(R.string.omnipod_pod_state_POD_IDs_has_been_removed), _lastResult.PodId), Notification.NORMAL);       //"Pod with Lot %d and Serial %d has been removed."
                RxBus.INSTANCE.send(new EventNewNotification(notification));
                SP.putString(R.string.key_omnipod_currentpodid,"");
            }
            else if (!_lastResult.PodRunning && result.PodRunning)
            {
                RxBus.INSTANCE.send(new EventDismissNotification(Notification.OMNIPY_POD_STATUS));
                Notification notification = new Notification(Notification.OMNIPY_POD_STATUS, MainApp.gs(R.string.omnipod_pod_state_Pod_is_activated_and_running), Notification.INFO);      //"Pod is activated and running"
                RxBus.INSTANCE.send(new EventNewNotification(notification));
                String currentPodID = SP.getString(R.string.key_omnipod_currentpodid,"");
                if ((currentPodID == "") || (currentPodID != result.PodId)) {
                    //New Pod
                    _log.debug("OMNICORE This looks like a new Pod");
                    SP.putString(R.string.key_omnipod_currentpodid,result.PodId);
                    SP.putLong(R.string.key_omnipod_pod_start_time,result.ResultDate);
                }
            }
            SP.putString(R.string.key_omnicore_last_result, _lastResult.asJson());
            if (result.Success) {
//                SP.putString(R.string.key_omnicore_last_successful_result, _lastResult.asJson());
                SP.putString(R.string.key_omnicore_last_commandstate, "Success");

            }
            else {
                SP.putString(R.string.key_omnicore_last_commandstate, "Failure");

            }
            _lastResult = result;
        }
        else
        {
            Notification cmdFailNotification = new Notification(Notification.OMNIPY_COMMAND_STATUS,
                    String.format(MainApp.gs(R.string.omnipod_command_state_lastcommand_failed), request.getRequestDetails()), Notification.NORMAL);
            RxBus.INSTANCE.send(new EventNewNotification(cmdFailNotification));

            if (_connected || !_connectionStatusKnown)
            {
                RxBus.INSTANCE.send(new EventDismissNotification(Notification.OMNIPY_CONNECTION_STATUS));
                Notification notification = new Notification(Notification.OMNIPY_CONNECTION_STATUS,
                        MainApp.gs(R.string.omnicore_not_connected), Notification.NORMAL, 60);
                RxBus.INSTANCE.send(new EventNewNotification(notification));
            }
            _connectionStatusKnown = true;
            _connected = false;
            _commandHistory.setRequestFailed(request);
            SP.putString(R.string.key_omnicore_last_commandstate, MainApp.gs(R.string.omnicore_not_connected));
        }

        RxBus.INSTANCE.send(new EventOmnipodUpdateGui());

        long delay = 60000;
        if (_connected) {
            if (_lastResult.PodRunning)
                delay = 150000;
            else
                delay = 30000;
        }

        _omniCoreTimer = new Timer();
        _omniCoreTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                _omniCoreTimer = null;
                getPodStatus();
                //getResult(new OmniCoreStatusRequest());
            }
        }, delay);

        return result;
    }


    private synchronized void processHistory(OmniCoreResult result, boolean wasRunning) {

        if (result.ResultsToDate == null)
            return;

        new HistoryProcessor(wasRunning).execute(result);
    }

    public void UpdateStatus() {
        if (IsConnected() && IsInitialized() && !IsBusy()) {
            long t0 = System.currentTimeMillis();
            if (t0 - _lastStatusRequest > 60000) {
                _lastStatusRequest = t0;

                getPodStatus();
            }
        }
    }

    public long GetLastUpdated() {
        return _lastResult.ResultDate;
    }

    public long getLastStatusResponse() {
        return _lastStatusResponse;
    }

    public String getPodStatusText()
    {
        if (!_lastResult.PodRunning)
            return MainApp.gs(R.string.omnipod_pod_status_Not_yet_running);
        else
            return MainApp.gs(R.string.omnipod_pod_status_Running);
    }

    public boolean IsProfileSet(Profile profile) {
        TimeZone tz = profile.getTimeZone();
        int offset_minutes = (tz.getRawOffset() + tz.getDSTSavings()) / (60 * 1000);
        if (_lastResult.UtcOffset != offset_minutes)
            return false;

        BigDecimal[] scheduleToVerify = getBasalScheduleFromProfile(profile);
        for(int i=0; i<48; i++)
            if (_lastResult.BasalSchedule[i].compareTo(scheduleToVerify[i]) != 0)
                return false;
        return true;
    }

    public double GetBaseBasalRate(long t) {
    //    long t = System.currentTimeMillis();
        t += _lastResult.UtcOffset;
        Date dt = new Date(t);
        int h = dt.getHours();
        int m = dt.getMinutes();

        int index = h * 2;
        if (m >= 30)
            index++;

        double basalMin = ConfigBuilderPlugin.getPlugin().getActivePump().getPumpDescription().basalMinimumRate;

        return _lastResult.BasalSchedule[index].doubleValue() > basalMin?  _lastResult.BasalSchedule[index].doubleValue() : basalMin ;
    }


    public double GetBaseBasalRate() {
        return GetBaseBasalRate(System.currentTimeMillis());
}

    public OmniCoreResult getPodStatus() {
        OmniCoreStatusRequest request = new OmniCoreStatusRequest();
        _commandHistory.addOrUpdateHistory(request,null);

        OmniCoreResult result = getResult(request);

        _commandHistory.addOrUpdateHistory(request,result);
        if (result != null && result.Success) {
            _lastStatusResponse = result.ResultDate;
        }

        //Set and Check Alerts
        //TODO: This should be a separate method

        if (_lastResult.PodRunning) {
            if (_lastResult.ReservoirLevel < SP.getInt(R.string.key_omnicore_alert_res_units,20)) {
                RxBus.INSTANCE.send(new EventDismissNotification(Notification.OMNIPY_POD_STATUS));
                Notification notification = new Notification(Notification.OMNIPY_POD_STATUS, MainApp.gs(R.string.omnipod_pod_alerts_Low_reservoir), Notification.NORMAL);      //"Pod is activated and running"
                RxBus.INSTANCE.send(new EventNewNotification(notification));
            }

            _alertProcessor.processExpirationAlerts(getExpirationTime(), getReservoirTime());

/*            if ((System.currentTimeMillis() - getPodAge()) > (SP.getInt(R.string.key_omnicore_alert_age_hours,72) * 60 * 60 * 1000)) {

                RxBus.INSTANCE.send(new EventDismissNotification(Notification.OMNIPY_POD_STATUS));
                Notification notification = new Notification(Notification.OMNIPY_POD_STATUS, MainApp.gs(R.string.omnipod_pod_alerts_Pod_expiring_soon), Notification.NORMAL);      //"Pod is activated and running"
                RxBus.INSTANCE.send(new EventNewNotification(notification));
            }*/
        }


        return result;
    }

    public OmniCoreResult SetNewBasalProfile(Profile profile) {
        OmniCoreResult result = null;
        if (IsConnected()) {
            TimeZone tz = profile.getTimeZone();
            int offset_minutes = (tz.getRawOffset() + tz.getDSTSavings()) / (60 * 1000);
            BigDecimal[] basalSchedule = getBasalScheduleFromProfile(profile);

            OmniCoreSetProfileRequest request = new OmniCoreSetProfileRequest(basalSchedule, offset_minutes);
            _commandHistory.addOrUpdateHistory(request,null);
            result = getResult(request);
            _commandHistory.addOrUpdateHistory(request,result);
        }
        return result;
    }

    public OmniCoreResult Bolus(DetailedBolusInfo detailedBolusInfo) {
        BigDecimal units = GetExactInsulinUnits(detailedBolusInfo.insulin);
        OmniCoreResult result = null;
        if (IsConnected() && IsInitialized()) {
            OmniCoreBolusRequest request = new OmniCoreBolusRequest(units);
            _commandHistory.addOrUpdateHistory(request,null, detailedBolusInfo);
            result = getResult(request);
            _commandHistory.addOrUpdateHistory(request,result);
        }
        return result;

    }

    public OmniCoreResult Bolus(BigDecimal bolusUnits) {
        OmniCoreResult result = null;
        if (IsConnected() && IsInitialized()) {
            OmniCoreBolusRequest request = new OmniCoreBolusRequest(bolusUnits);
            _commandHistory.addOrUpdateHistory(request,null);
            result = getResult(request);
            _commandHistory.addOrUpdateHistory(request,result);
        }
        return result;
    }

    public OmniCoreResult CancelBolus() {
        OmniCoreResult result = null;
        if (IsConnected() && IsInitialized()) {
            OmniCoreCancelBolusRequest request = new OmniCoreCancelBolusRequest();
            _commandHistory.addOrUpdateHistory(request,null);
            result = getResult(request);
            _commandHistory.addOrUpdateHistory(request,result);
        }
        return result;
    }

    public OmniCoreResult SetTempBasal(BigDecimal iuRate, BigDecimal durationHours) {
        OmniCoreResult result = null;
        if (IsConnected() && IsInitialized()) {
            OmniCoreTempBasalRequest request = new OmniCoreTempBasalRequest(iuRate, durationHours);
            _commandHistory.addOrUpdateHistory(request,null);
            result = getResult(request);
            _commandHistory.addOrUpdateHistory(request,result);
        }
        return result;
    }

    public OmniCoreResult CancelTempBasal() {
        OmniCoreResult result = null;
        if (IsConnected() && IsInitialized()) {
            OmniCoreCancelTempBasalRequest request = new OmniCoreCancelTempBasalRequest();
            _commandHistory.addOrUpdateHistory(request,null);
            result = getResult(request);
            _commandHistory.addOrUpdateHistory(request,result);
        }
        return result;
    }

    public String GetPodId() {
        if (!_lastResult.PodRunning)
            return "NO POD";
        else
            return _lastResult.PodId;
    }

    public String GetStatusShort() {
        if (_lastResult.PodRunning) {
                return "OK";
        }
        return "NO POD";
    }




    public BigDecimal GetExactInsulinUnits(double iu)
    {
        BigDecimal big20 = new BigDecimal("20");
        // round to 0.05's complements
        return new BigDecimal(iu).multiply(big20).setScale(0, RoundingMode.HALF_UP).setScale(2).divide(big20);
    }

    public BigDecimal GetExactHourUnits(int minutes)
    {
        BigDecimal big30 = new BigDecimal("30");
        return new BigDecimal(minutes).divide(big30).setScale(0, RoundingMode.HALF_UP).setScale(1).divide(new BigDecimal(2));
    }

    public BigDecimal[] getBasalScheduleFromProfile(Profile profile)
    {
        BigDecimal[] basalSchedule = new BigDecimal[48];
        int secondsSinceMidnight = 0;
        for(int i=0; i<48; i++)
        {
            basalSchedule[i] = GetExactInsulinUnits(profile.getBasalTimeFromMidnight(secondsSinceMidnight));
            secondsSinceMidnight += 60*30;
        }
        return basalSchedule;
    }

    public double GetReservoirLevel() {

        return _lastResult.ReservoirLevel;
    }

    public long getPodStartTime() {
        long cpAge = 0;
        CareportalEvent podChangeEvent = MainApp.getDbHelper().getLastCareportalEvent(CareportalEvent.SITECHANGE);
        if (podChangeEvent != null) {
            cpAge = podChangeEvent.date;
        }
        long podAge =  SP.getLong(R.string.key_omnipod_pod_start_time, cpAge);
        return podAge;
    }

    public long getExpirationTime() {
        return getPodStartTime() + (80 * 60 * 60 *1000);
    }

    public long getBlackoutExpirationTime() {
        long soonestExpire = getExpirationTime() < getReservoirTime() ? getExpirationTime() : getReservoirTime();

        return _alertProcessor.getAdjustedExpirationTime(soonestExpire);

    }

    public long getReservoirTime() {
        _log.debug("OMNICORE Finding BaseBasal");

        double insulinRemaining = GetReservoirLevel();

        _log.debug("OMNICORE getReservoirTime: " + insulinRemaining + "U Remaining");


        if (insulinRemaining > 50) {
            _log.debug("OMNICORE getReservoirTime: More than 50U");
            return getExpirationTime();
        }
        else {
            try {
                _log.debug("OMNICORE getReservoirTime: Less than 50U. Adding up basal");

                //loop through profile adding up base basal. Stop when we hit insulinRemaining
                double totalInsulin = 0;
                double currentRate = GetBaseBasalRate();
                _log.debug("OMNICORE getReservoirTime: Current Basal Rate: " + currentRate);

                long currentTime = System.currentTimeMillis();
                _log.debug("OMNICORE getReservoirTime: Current Time: " + DateUtil.dateAndTimeString(currentTime));

                long timeRemainingInHour = (1000 * 60 * 60) - currentTime % (1000 * 60 * 60);
                _log.debug("OMNICORE getReservoirTime: Time Remaining in Hour: " + timeRemainingInHour + "ms - " +timeRemainingInHour/(1000  * 60) + "Min");

                long startOfHour = currentTime - timeRemainingInHour;
                _log.debug("OMNICORE getReservoirTime: Time Rounded to hour:" + DateUtil.dateAndTimeString(startOfHour));

                totalInsulin += currentRate * timeRemainingInHour/(1000 * 60 * 60);
                _log.debug("OMNICORE getReservoirTime: Basal for this hour: " +totalInsulin);

                long timeToCheck = startOfHour + (1000 * 60 * 60) + 1;
                while(totalInsulin < insulinRemaining) {
                    _log.debug("OMNICORE getReservoirTime: Checking Time: " + DateUtil.dateAndTimeString(timeToCheck));
                    currentRate = GetBaseBasalRate(timeToCheck);
                    _log.debug("OMNICORE getReservoirTime: Basal Rate: " + currentRate);
                    totalInsulin += currentRate;
                    _log.debug("OMNICORE getReservoirTime: Total Basal now: " + totalInsulin);
                    timeToCheck += (1000 * 60 * 60);
                }
                _log.debug("OMNICORE getReservoirTime: Basal will exceed reservoir by: " + DateUtil.dateAndTimeString(timeToCheck));

                return timeToCheck - (1000 * 60 * 60);


            }
            catch(Exception e) {
                return getExpirationTime();
            }
        }
    }

    public int getBatteryLevel() {
        return _lastResult.BatteryLevel;
    }



    public OmniCoreCommandHistory getCommandHistory() {
        return _commandHistory;
    }
}


class HistoryProcessor extends AsyncTask<OmniCoreResult,Void,Void>
{
    class BasalParameters {
        public BigDecimal BasalRate;
        public BigDecimal Duration;
    }

    class BolusParameters {
        public BigDecimal ImmediateUnits;
    }

    class CancelBolusParameters {
        public BigDecimal NotDeliveredInsulin;
    }

    private boolean _podWasRunning;
    public HistoryProcessor(boolean podWasRunning)
    {
        _podWasRunning = podWasRunning;
    }

    private DetailedBolusInfo getBolusInfoFromTreatments(long pumpId, List<Treatment> treatments)
    {
        for (Treatment treatment : treatments) {
            if (treatment.pumpId == pumpId)
            {
                DetailedBolusInfo detailedBolusInfo = new DetailedBolusInfo();
                detailedBolusInfo.pumpId = treatment.pumpId;
                detailedBolusInfo.insulin = treatment.insulin;
                detailedBolusInfo.isSMB = treatment.isSMB;
                detailedBolusInfo.date = treatment.date;
                detailedBolusInfo.source = treatment.source;
                return detailedBolusInfo;
            }
        }
        return null;
    }

    private TemporaryBasal getTempBasal(long pumpId, Intervals<TemporaryBasal> tempBasals)
    {
        for (TemporaryBasal tempBasal : tempBasals.getList()) {
            if (tempBasal.pumpId == pumpId)
                return tempBasal;
        }
        return null;
    }


    @Override
    protected Void doInBackground(OmniCoreResult... omniCoreResults) {
        OmniCoreResult result = (OmniCoreResult)omniCoreResults[0];
        TreatmentsPlugin treatmentsPlugin = TreatmentsPlugin.getPlugin();
        List<Treatment> treatments = treatmentsPlugin.getTreatmentsFromHistory();
        Intervals<TemporaryBasal> temporaryBasals = treatmentsPlugin.getTemporaryBasalsFromHistory();
        //ProfileIntervals<ProfileSwitch> profileSwitches = treatmentsPlugin.getProfileSwitchesFromHistory();
        OmniCoreCommandHistory commandHistory = OmnipodPlugin.getPlugin().getPdm().getCommandHistory();

        DetailedBolusInfo cancelBolusCandidate = null;
        OmniCoreHistoricalResult cancelBolusHistoricalCandidate = null;

        for (JsonElement historicalResultJson : result.ResultsToDate) {

            OmniCoreHistoricalResult historicalResult = new Gson()
                    .fromJson(historicalResultJson.toString(), OmniCoreHistoricalResult.class);

            OmniCoreCommandHistoryItem hi = commandHistory.getMatchingHistoryItem(historicalResult.ResultDate);
            if (hi != null) {
                hi.setSucceeded();
            }

            switch (historicalResult.Type) {
                case SetBasalSchedule:
                    break;
                case Bolus:
                    cancelBolusHistoricalCandidate = historicalResult;
                    DetailedBolusInfo existingBolusInfo =
                            getBolusInfoFromTreatments(historicalResult.ResultDate, treatments);
                    if (existingBolusInfo == null) {
                        BolusParameters p1 = new Gson()
                                .fromJson(historicalResult.Parameters, BolusParameters.class);
                        DetailedBolusInfo detailedBolusInfo = new DetailedBolusInfo();
                        detailedBolusInfo.pumpId = historicalResult.ResultDate;
                        detailedBolusInfo.insulin = p1.ImmediateUnits.doubleValue();
                        //detailedBolusInfo.isSMB = false;
                        detailedBolusInfo.isSMB = hi != null ? hi.getBolusInfo().isSMB : false;
                        detailedBolusInfo.carbs = hi !=  null ? hi.getBolusInfo().carbs : 0;
                        detailedBolusInfo.date = historicalResult.ResultDate;
                        detailedBolusInfo.source = Source.PUMP;
                        treatmentsPlugin.addToHistoryTreatment(detailedBolusInfo, true);
                        cancelBolusCandidate = detailedBolusInfo;
                    } else {
                        cancelBolusCandidate = existingBolusInfo;
                    }


                    break;
                case CancelBolus:
                    if (cancelBolusCandidate != null && cancelBolusHistoricalCandidate != null) {
                        BolusParameters canceledBolusParameters = new Gson()
                                .fromJson(cancelBolusHistoricalCandidate.Parameters, BolusParameters.class);

                        CancelBolusParameters p2 = new Gson().fromJson(historicalResult.Parameters, CancelBolusParameters.class);
                        cancelBolusCandidate.insulin = canceledBolusParameters.ImmediateUnits.subtract(p2.NotDeliveredInsulin).doubleValue();
                        treatmentsPlugin.addToHistoryTreatment(cancelBolusCandidate, true);
                    }
                    break;
                case SetTempBasal:
                    TemporaryBasal tempBasalRecorded = getTempBasal(historicalResult.ResultDate,
                            temporaryBasals);
                    if (tempBasalRecorded == null) {
                        BasalParameters p3 = new Gson().fromJson(historicalResult.Parameters,
                                BasalParameters.class);

                        double basalRate = p3.BasalRate.doubleValue();
                        int minutes = p3.Duration.multiply(new BigDecimal(60)).intValue();
                        TemporaryBasal tempBasal = new TemporaryBasal()
                                .date(historicalResult.ResultDate)
                                .absolute(basalRate)
                                .duration(minutes)
                                .pumpId(historicalResult.ResultDate)
                                .source(Source.PUMP);
                        treatmentsPlugin.addToHistoryTempBasal(tempBasal);
                    }
                    break;
                case CancelTempBasal:
                    TemporaryBasal tempBasalCancelRecorded = getTempBasal(historicalResult.ResultDate,
                            temporaryBasals);
                    if (tempBasalCancelRecorded == null) {
                        TemporaryBasal tempStop = new TemporaryBasal()
                                .date(historicalResult.ResultDate)
                                .pumpId(historicalResult.ResultDate)
                                .source(Source.PUMP);

                        treatmentsPlugin.addToHistoryTempBasal(tempStop);
                    }
                    break;
                case StartExtendedBolus:
                    break;
                case StopExtendedBolus:
                    break;
                case Status:
                    break;
            }

            TemporaryBasal zeroBasal = new TemporaryBasal()
                    .date(historicalResult.ResultDate)
                    .absolute(0)
                    .duration(24 * 60 * 14)
                    .pumpId(historicalResult.ResultDate)
                    .source(Source.PUMP);

            if (!_podWasRunning)
            {
                TemporaryBasal tempBasalAtTime = treatmentsPlugin.getTempBasalFromHistory(historicalResult.ResultDate);
                if (!historicalResult.PodRunning)
                {
                    if (tempBasalAtTime == null)
                    {
                        treatmentsPlugin.addToHistoryTempBasal(zeroBasal);
                    }
                }
                else
                {
                    if (tempBasalAtTime != null)
                    {
                        TemporaryBasal tempBasalCancel = new TemporaryBasal()
                                .date(historicalResult.ResultDate)
                                .pumpId(historicalResult.ResultDate)
                                .source(Source.PUMP);
                        treatmentsPlugin.addToHistoryTempBasal(tempBasalCancel);
                    }
                }
            }
            else
            {
                if (!historicalResult.PodRunning)
                {
                    treatmentsPlugin.addToHistoryTempBasal(zeroBasal);
                }
            }
            _podWasRunning = historicalResult.PodRunning;
        }
        return null;
    }
}
