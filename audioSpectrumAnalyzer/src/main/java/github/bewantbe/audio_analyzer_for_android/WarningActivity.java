package github.bewantbe.audio_analyzer_for_android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;

public class WarningActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        setContentView(R.layout.warning);
    }public void dialog()
    {
        new AlertDialog.Builder(this)
                .setTitle("Warning! Dog disconnected")
                .setMessage("Is dog on?")
                .setNegativeButton("No",null)
                .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        //add autoconnect function
                    }
                }).create().show();;
    }
}
