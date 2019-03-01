package github.bewantbe.audio_analyzer_for_android;

import android.bluetooth.BluetoothAdapter;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.WindowManager;

import
import com.wowwee.bluetoothrobotcontrollib.chip.ChipRobot;
import com.wowwee.bluetoothrobotcontrollib.chip.ChipRobotFinder;
import github.bewantbe.audio_analyzer_for_android.ConnectFragment;
import github.bewantbe.audio_analyzer_for_android.utils.FragmentHelper;

import java.util.List;

public class toDogActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.todog);

        BluetoothAdapter.getDefaultAdapter();
        FragmentHelper.switchFragment(getSupportFragmentManager(), new ConnectFragment(), R.id.view_id_content, false);
    }

    @Override
    public void onStop() {
        super.onStop();
        for (ChipRobot robot : (List<ChipRobot>)ChipRobotFinder.getInstance().getChipRobotConnectedList()){
            robot.disconnect();
        }
        System.exit(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // disable idle timer
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        for (ChipRobot robot : (List<ChipRobot>)ChipRobotFinder.getInstance().getChipRobotConnectedList()){
            robot.disconnect();
        }

        BluetoothRobot.unbindBluetoothLeService(MainActivity.this);

        System.exit(0);
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }
}
