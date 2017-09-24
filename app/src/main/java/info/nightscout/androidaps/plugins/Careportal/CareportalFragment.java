package info.nightscout.androidaps.plugins.Careportal;


import android.app.Activity;
import android.os.Bundle;
import android.support.v4.app.FragmentManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.squareup.otto.Subscribe;

import info.nightscout.androidaps.BuildConfig;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.ProfileStore;
import info.nightscout.androidaps.db.CareportalEvent;
import info.nightscout.androidaps.events.EventCareportalEventChange;
import info.nightscout.androidaps.plugins.Careportal.Dialogs.NewNSTreatmentDialog;
import info.nightscout.androidaps.plugins.Common.SubscriberFragment;
import info.nightscout.androidaps.plugins.ConfigBuilder.ConfigBuilderPlugin;
import info.nightscout.androidaps.plugins.Overview.OverviewFragment;

public class CareportalFragment extends SubscriberFragment implements View.OnClickListener {

    static CareportalPlugin careportalPlugin;

    TextView iage;
    TextView cage;
    TextView sage;
    TextView pbage;

    View statsLayout;
    LinearLayout butonsLayout;
    View noProfileView;

    static public CareportalPlugin getPlugin() {
        if (careportalPlugin == null) {
            careportalPlugin = new CareportalPlugin();
        }
        return careportalPlugin;
    }

    //                                                    date,bg,insulin,carbs,prebolus,duration,percent,absolute,profile,split,temptarget
    public static final OptionsToShow BGCHECK = new OptionsToShow(R.id.careportal_bgcheck, R.string.careportal_bgcheck).date().bg();
    public static final OptionsToShow SNACKBOLUS = new OptionsToShow(R.id.careportal_snackbolus, R.string.careportal_snackbolus).date().bg().insulin().carbs().prebolus();
    public static final OptionsToShow MEALBOLUS = new OptionsToShow(R.id.careportal_mealbolus, R.string.careportal_mealbolus).date().bg().insulin().carbs().prebolus();
    public static final OptionsToShow CORRECTIONBOLUS = new OptionsToShow(R.id.careportal_correctionbolus, R.string.careportal_correctionbolus).date().bg().insulin().carbs().prebolus();
    public static final OptionsToShow CARBCORRECTION = new OptionsToShow(R.id.careportal_carbscorrection, R.string.careportal_carbscorrection).date().bg().carbs();
    public static final OptionsToShow COMBOBOLUS = new OptionsToShow(R.id.careportal_combobolus, R.string.careportal_combobolus).date().bg().insulin().carbs().prebolus().duration().split();
    public static final OptionsToShow ANNOUNCEMENT = new OptionsToShow(R.id.careportal_announcement, R.string.careportal_announcement).date().bg();
    public static final OptionsToShow NOTE = new OptionsToShow(R.id.careportal_note, R.string.careportal_note).date().bg().duration();
    public static final OptionsToShow QUESTION = new OptionsToShow(R.id.careportal_question, R.string.careportal_question).date().bg();
    public static final OptionsToShow EXERCISE = new OptionsToShow(R.id.careportal_exercise, R.string.careportal_exercise).date().duration();
    public static final OptionsToShow SITECHANGE = new OptionsToShow(R.id.careportal_pumpsitechange, R.string.careportal_pumpsitechange).date().bg();
    public static final OptionsToShow SENSORSTART = new OptionsToShow(R.id.careportal_cgmsensorstart, R.string.careportal_cgmsensorstart).date().bg();
    public static final OptionsToShow SENSORCHANGE = new OptionsToShow(R.id.careportal_cgmsensorinsert, R.string.careportal_cgmsensorinsert).date().bg();
    public static final OptionsToShow INSULINCHANGE = new OptionsToShow(R.id.careportal_insulincartridgechange, R.string.careportal_insulincartridgechange).date().bg();
    public static final OptionsToShow PUMPBATTERYCHANGE = new OptionsToShow(R.id.careportal_pumpbatterychange, R.string.careportal_pumpbatterychange).date().bg();
    public static final OptionsToShow TEMPBASALSTART = new OptionsToShow(R.id.careportal_tempbasalstart, R.string.careportal_tempbasalstart).date().bg().duration().percent().absolute();
    public static final OptionsToShow TEMPBASALEND = new OptionsToShow(R.id.careportal_tempbasalend, R.string.careportal_tempbasalend).date().bg();
    public static final OptionsToShow PROFILESWITCH = new OptionsToShow(R.id.careportal_profileswitch, R.string.careportal_profileswitch).date().duration().profile();
    public static final OptionsToShow PROFILESWITCHDIRECT = new OptionsToShow(R.id.careportal_profileswitch, R.string.careportal_profileswitch).duration().profile();
    public static final OptionsToShow OPENAPSOFFLINE = new OptionsToShow(R.id.careportal_openapsoffline, R.string.careportal_openapsoffline).date().duration();
    public static final OptionsToShow TEMPTARGET = new OptionsToShow(R.id.careportal_temporarytarget, R.string.careportal_temporarytarget).date().duration().tempTarget();

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.careportal_fragment, container, false);

        view.findViewById(R.id.careportal_bgcheck).setOnClickListener(this);
        view.findViewById(R.id.careportal_announcement).setOnClickListener(this);
        view.findViewById(R.id.careportal_cgmsensorinsert).setOnClickListener(this);
        view.findViewById(R.id.careportal_cgmsensorstart).setOnClickListener(this);
        view.findViewById(R.id.careportal_combobolus).setOnClickListener(this);
        view.findViewById(R.id.careportal_correctionbolus).setOnClickListener(this);
        view.findViewById(R.id.careportal_carbscorrection).setOnClickListener(this);
        view.findViewById(R.id.careportal_exercise).setOnClickListener(this);
        view.findViewById(R.id.careportal_insulincartridgechange).setOnClickListener(this);
        view.findViewById(R.id.careportal_pumpbatterychange).setOnClickListener(this);
        view.findViewById(R.id.careportal_mealbolus).setOnClickListener(this);
        view.findViewById(R.id.careportal_note).setOnClickListener(this);
        view.findViewById(R.id.careportal_profileswitch).setOnClickListener(this);
        view.findViewById(R.id.careportal_pumpsitechange).setOnClickListener(this);
        view.findViewById(R.id.careportal_question).setOnClickListener(this);
        view.findViewById(R.id.careportal_snackbolus).setOnClickListener(this);
        view.findViewById(R.id.careportal_tempbasalend).setOnClickListener(this);
        view.findViewById(R.id.careportal_tempbasalstart).setOnClickListener(this);
        view.findViewById(R.id.careportal_openapsoffline).setOnClickListener(this);
        view.findViewById(R.id.careportal_temporarytarget).setOnClickListener(this);

        iage = (TextView) view.findViewById(R.id.careportal_insulinage);
        cage = (TextView) view.findViewById(R.id.careportal_canulaage);
        sage = (TextView) view.findViewById(R.id.careportal_sensorage);
        pbage = (TextView) view.findViewById(R.id.careportal_pbage);

        statsLayout = (View) view.findViewById(R.id.careportal_stats);

        noProfileView = (View) view.findViewById(R.id.profileview_noprofile);
        butonsLayout = (LinearLayout) view.findViewById(R.id.careportal_buttons);

        ProfileStore profileStore = ConfigBuilderPlugin.getActiveProfileInterface().getProfile();
        if (profileStore == null) {
            noProfileView.setVisibility(View.VISIBLE);
            butonsLayout.setVisibility(View.GONE);
        } else {
            noProfileView.setVisibility(View.GONE);
            butonsLayout.setVisibility(View.VISIBLE);
        }

        if (BuildConfig.NSCLIENTOLNY)
            statsLayout.setVisibility(View.GONE); // visible on overview

        updateGUI();
        return view;
    }

    @Override
    public void onClick(View view) {
        action(view.getId(), getFragmentManager());
    }

    public static void action(int id, FragmentManager manager) {
        NewNSTreatmentDialog newDialog = new NewNSTreatmentDialog();
        switch (id) {
            case R.id.careportal_bgcheck:
                newDialog.setOptions(BGCHECK, R.string.careportal_bgcheck);
                break;
            case R.id.careportal_announcement:
                newDialog.setOptions(ANNOUNCEMENT, R.string.careportal_announcement);
                break;
            case R.id.careportal_cgmsensorinsert:
                newDialog.setOptions(SENSORCHANGE, R.string.careportal_cgmsensorinsert);
                break;
            case R.id.careportal_cgmsensorstart:
                newDialog.setOptions(SENSORSTART, R.string.careportal_cgmsensorstart);
                break;
            case R.id.careportal_combobolus:
                newDialog.setOptions(COMBOBOLUS, R.string.careportal_combobolus);
                break;
            case R.id.careportal_correctionbolus:
                newDialog.setOptions(CORRECTIONBOLUS, R.string.careportal_correctionbolus);
                break;
            case R.id.careportal_carbscorrection:
                newDialog.setOptions(CARBCORRECTION, R.string.careportal_carbscorrection);
                break;
            case R.id.careportal_exercise:
                newDialog.setOptions(EXERCISE, R.string.careportal_exercise);
                break;
            case R.id.careportal_insulincartridgechange:
                newDialog.setOptions(INSULINCHANGE, R.string.careportal_insulincartridgechange);
                break;
            case R.id.careportal_pumpbatterychange:
                newDialog.setOptions(PUMPBATTERYCHANGE, R.string.careportal_pumpbatterychange);
                break;
            case R.id.careportal_mealbolus:
                newDialog.setOptions(MEALBOLUS, R.string.careportal_mealbolus);
                break;
            case R.id.careportal_note:
                newDialog.setOptions(NOTE, R.string.careportal_note);
                break;
            case R.id.careportal_profileswitch:
                PROFILESWITCH.executeProfileSwitch = false;
                newDialog.setOptions(PROFILESWITCH, R.string.careportal_profileswitch);
                break;
            case R.id.careportal_pumpsitechange:
                newDialog.setOptions(SITECHANGE, R.string.careportal_pumpsitechange);
                break;
            case R.id.careportal_question:
                newDialog.setOptions(QUESTION, R.string.careportal_question);
                break;
            case R.id.careportal_snackbolus:
                newDialog.setOptions(SNACKBOLUS, R.string.careportal_snackbolus);
                break;
            case R.id.careportal_tempbasalstart:
                newDialog.setOptions(TEMPBASALSTART, R.string.careportal_tempbasalstart);
                break;
            case R.id.careportal_tempbasalend:
                newDialog.setOptions(TEMPBASALEND, R.string.careportal_tempbasalend);
                break;
            case R.id.careportal_openapsoffline:
                newDialog.setOptions(OPENAPSOFFLINE, R.string.careportal_openapsoffline);
                break;
            case R.id.careportal_temporarytarget:
                TEMPTARGET.executeTempTarget = false;
                newDialog.setOptions(TEMPTARGET, R.string.careportal_temporarytarget);
                break;
            default:
                newDialog = null;
        }
        if (newDialog != null)
            newDialog.show(manager, "NewNSTreatmentDialog");
    }

    @Subscribe
    public void onStatusEvent(final EventCareportalEventChange c) {
        updateGUI();
    }

    @Override
    protected void updateGUI() {
        Activity activity = getActivity();
        updateAge(activity, sage, iage, cage, pbage);
    }

    public static void updateAge(Activity activity, final TextView sage, final TextView iage, final TextView cage, final TextView pbage) {
        if (activity != null) {
            activity.runOnUiThread(
                    new Runnable() {
                        @Override
                        public void run() {
                            CareportalEvent careportalEvent;
                            String notavailable = OverviewFragment.shorttextmode ? "-" : MainApp.sResources.getString(R.string.notavailable);
                            if (sage != null) {
                                careportalEvent = MainApp.getDbHelper().getLastCareportalEvent(CareportalEvent.SENSORCHANGE);
                                sage.setText(careportalEvent != null ? careportalEvent.age() : notavailable);
                            }
                            if (iage != null) {
                                careportalEvent = MainApp.getDbHelper().getLastCareportalEvent(CareportalEvent.INSULINCHANGE);
                                iage.setText(careportalEvent != null ? careportalEvent.age() : notavailable);
                            }
                            if (cage != null) {
                                careportalEvent = MainApp.getDbHelper().getLastCareportalEvent(CareportalEvent.SITECHANGE);
                                cage.setText(careportalEvent != null ? careportalEvent.age() : notavailable);
                            }
                            if (pbage != null) {
                                careportalEvent = MainApp.getDbHelper().getLastCareportalEvent(CareportalEvent.PUMPBATTERYCHANGE);
                                pbage.setText(careportalEvent != null ? careportalEvent.age() : notavailable);
                            }
                        }
                    }
            );
        }
    }

}
