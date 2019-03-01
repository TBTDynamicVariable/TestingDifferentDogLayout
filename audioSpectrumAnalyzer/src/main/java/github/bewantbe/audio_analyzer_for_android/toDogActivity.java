package github.bewantbe.audio_analyzer_for_android;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;


import com.wowwee.bluetoothrobotcontrollib.chip.ChipRobot;
import com.wowwee.bluetoothrobotcontrollib.chip.ChipRobotFinder;


import java.util.ArrayList;
import java.util.List;

public class toDogActivity extends Activity implements ChipRobot.ChipRobotInterface {

    ChipBaseFragment baseFragmentReady;
    private static final int REQUEST_ENABLE_BT = 1;
    private BluetoothAdapter mBluetoothAdapter;
    private Handler handler;
    private ListView listView;
    List<String> robotNameList;

    @Override
    public void onCreate(Bundle savedInstances) {

        super.onCreate(savedInstances);
        setContentView(R.layout.todog);

        final int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;

        //getActivity().getWindow().getDecorView().setSystemUiVisibility(flags);


        //View view = inflater.inflate(R.layout.fragment_connect, container, false);

        listView = (ListView)findViewById(R.id.connectionTable);
        String[] robotNameArr = {"Please turn on CHIP"};
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(toDogActivity.this, android.R.layout.simple_list_item_1, android.R.id.text1, robotNameArr);
        listView.setAdapter(adapter);

        Button refreshBtn = (Button)findViewById(R.id.refreshBtn);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ChipRobotFinder.getInstance().clearFoundChipList();
                scanLeDevice(false);
                updateChipList();
                scanLeDevice(true);
            }
        });

        this.initBluetooth();

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position,
                                    long id) {
                if (ChipRobotFinder.getInstance().getChipFoundList().size() > 0) {
                    String targetRobotName = robotNameList.get(position);
                    for (ChipRobot robot : (List<ChipRobot>) ChipRobotFinder.getInstance().getChipFoundList()) {
                        if (robot.getName().contentEquals(targetRobotName)) {
                            final ChipRobot connectChipRobot = robot;
                            toDogActivity.this.runOnUiThread(new Runnable() {
                                public void run() {
                                    connect(connectChipRobot);
                                    // Stop scan Chip
                                    scanLeDevice(false);
                                }
                            });
                            break;
                        }
                    }
                }
            }
        });
        openAnalyzerActivity();
    }

    void connect(ChipRobot robot) {
        robot.setCallbackInterface(this);
        robot.connect(toDogActivity.this);
    }

    @Override
    public void onResume() {
        super.onResume();

        // Register ChipRobotFinder broadcast receiver
        this.registerReceiver(mChipFinderBroadcastReceiver, ChipRobotFinder.getChipRobotFinderIntentFilter());

        // Ensures Bluetooth is enabled on the device.  If Bluetooth is not currently enabled,
        // fire an intent to display a dialog asking the user to grant permission to enable it.
        if (mBluetoothAdapter != null && !mBluetoothAdapter.isEnabled()) {
            if (!mBluetoothAdapter.isEnabled()) {
                try {
                    Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);

                } catch (ActivityNotFoundException ex) {
                }
            }
        }

        // Start scan
        ChipRobotFinder.getInstance().clearFoundChipList();
        scanLeDevice(false);
        updateChipList();
        scanLeDevice(true);
    }

    @Override
    public void onPause() {
        super.onPause();
        this.unregisterReceiver(mChipFinderBroadcastReceiver);
        scanLeDevice(false);
    }

    private void initBluetooth(){
        final BluetoothManager bluetoothManager = (BluetoothManager) this.getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();
        ChipRobotFinder.getInstance().setBluetoothAdapter(mBluetoothAdapter);
        ChipRobotFinder.getInstance().setApplicationContext(this);
    }

    private void scanLeDevice(final boolean enable) {
        if (enable) {
            Log.d("ChipScan", "Scan Le device start");
            // Stops scanning after a pre-defined scan period.
            ChipRobotFinder.getInstance().scanForChipContinuous();
        }else{
            Log.d("ChipScan", "Scan Le device stop");
            ChipRobotFinder.getInstance().stopScanForChipContinuous();
        }
    }

    private void updateChipList(){
        robotNameList = new ArrayList();
        for (ChipRobot robot : (List<ChipRobot>)ChipRobotFinder.getInstance().getChipFoundList()) {
            robotNameList.add(robot.getName());
        }

        String[] robotNameArr;
        if (robotNameList.size() > 0)
            robotNameArr = robotNameList.toArray(new String[0]);
        else {
            robotNameArr = new String[1];
            robotNameArr[0] = "Please turn on CHIP";
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, android.R.id.text1, robotNameArr);
        listView.setAdapter(adapter);
    }

    private final BroadcastReceiver mChipFinderBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (ChipRobotFinder.ChipRobotFinder_ChipFound.equals(action)){
                BluetoothDevice device = (BluetoothDevice)(intent.getExtras().get("BluetoothDevice"));
                Log.d("BLE", "ChipScanFragment broadcast receiver found Chip: " + device.getName());
                updateChipList();
            } else if (ChipRobotFinder.ChipRobotFinder_ChipListCleared.equals(action)) {
                updateChipList();
            }
        }
    };




    public void openAnalyzerActivity(){
            Intent intent = new Intent(this,AnalyzerActivity.class);
            startActivity(intent);
    }

    @Override
    public void chipDeviceReady(ChipRobot chipRobot) {

    }

    @Override
    public void chipDeviceDisconnected(ChipRobot chipRobot) {

    }

    @Override
    public void chipDidReceiveVolumeLevel(ChipRobot chipRobot, byte b) {

    }

    @Override
    public void chipDidReceivedBatteryLevel(ChipRobot chipRobot, float v, byte b, byte b1) {

    }

    @Override
    public void chipDidReceiveDogVersionWithBodyHardware(int i, int i1, int i2, int i3, int i4, int i5, int i6, int i7, int i8, int i9) {

    }

    @Override
    public void chipDidSendAdultOrKidSpeed() {

    }

    @Override
    public void chipDidReceiveAdultOrKidSpeed(byte b) {

    }

    @Override
    public void chipDidReceiveEyeRGBBrightness(byte b) {

    }

    @Override
    public void chipDidReceiveCurrentClock(int i, int i1, int i2, int i3, int i4, int i5, int i6) {

    }

    @Override
    public void chipDidReceiveCurrentAlarm(int i, int i1, int i2, int i3, int i4) {

    }

    @Override
    public void chipDidReceiveBodyconStatus(int i) {

    }
}
