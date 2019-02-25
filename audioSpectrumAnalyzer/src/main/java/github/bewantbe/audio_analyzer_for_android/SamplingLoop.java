/* Copyright 2014 Eddy Xiao <bewantbe@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package github.bewantbe.audio_analyzer_for_android;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.audiofx.AutomaticGainControl;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.util.Arrays;

/**
 * Read a snapshot of audio data at a regular interval, and compute the FFT
 * @author suhler@google.com
 *         bewantbe@gmail.com
 * Ref:
 *   https://developer.android.com/guide/topics/media/mediarecorder.html#example
 *   https://developer.android.com/reference/android/media/audiofx/AutomaticGainControl.html
 *
 * TODO:
 *   See also: High-Performance Audio
 *   https://developer.android.com/ndk/guides/audio/index.html
 *   https://developer.android.com/ndk/guides/audio/aaudio/aaudio.html
 */

class SamplingLoop extends Thread {
    private final String TAG = "SamplingLoop";
    private volatile boolean isRunning = true;
    private volatile boolean isPaused = false;
    private STFT stft;   // use with care
    private final AnalyzerParameters analyzerParam;

    private SineGenerator sineGen1;
    private SineGenerator sineGen2;
    private double[] spectrumDBcopy;   // XXX, transfers data from SamplingLoop to AnalyzerGraphic

    private final AnalyzerActivity activity;

    volatile double wavSecRemain;
    volatile double wavSec = 0;

    /**************AV Definitions Start*******************************************************/
    private static final int WORDDURATION_AFTERDETECTION = 1100;//milliseconds. We will keep recording sound for this duration after the fist vocalization is detected. Please keep to the minimum to reduce delay in reward.
    private static final int WORDDURATION_BEFOREDETECTION = 600;//milliseconds
    //private static final int WORDDURATION = WORDDURATION_BEFOREDETECTION + WORDDURATION_AFTERDETECTION;//milliseconds

    private int nBuffers=0;
    private double[] rmsHistory;
    private static final int THRESHOLDBUFFERDURATION = 1000;// millisecons = 1sec
    public static StringBuilder  classIndicator = new StringBuilder("-");//this is used to indicate each chunk's classification for the presence of vocalization
    private final boolean bReportAnalysis=false;
    /**************AV Definitions End*******************************************************/

    SamplingLoop(AnalyzerActivity _activity, AnalyzerParameters _analyzerParam) {
        activity = _activity;
        analyzerParam = _analyzerParam;

        isPaused = ((SelectorText) activity.findViewById(R.id.run)).getValue().equals("stop");
        // Signal sources for testing
        double fq0 = Double.parseDouble(activity.getString(R.string.test_signal_1_freq1));
        double amp0 = Math.pow(10, 1/20.0 * Double.parseDouble(activity.getString(R.string.test_signal_1_db1)));
        double fq1 = Double.parseDouble(activity.getString(R.string.test_signal_2_freq1));
        double amp1 = Math.pow(10, 1/20.0 * Double.parseDouble(activity.getString(R.string.test_signal_2_db1)));
        double fq2 = Double.parseDouble(activity.getString(R.string.test_signal_2_freq2));
        double amp2 = Math.pow(10, 1/20.0 * Double.parseDouble(activity.getString(R.string.test_signal_2_db2)));
        if (analyzerParam.audioSourceId == 1000) {
            sineGen1 = new SineGenerator(fq0, analyzerParam.sampleRate, analyzerParam.SAMPLE_VALUE_MAX * amp0);
        } else {
            sineGen1 = new SineGenerator(fq1, analyzerParam.sampleRate, analyzerParam.SAMPLE_VALUE_MAX * amp1);
        }
        sineGen2 = new SineGenerator(fq2, analyzerParam.sampleRate, analyzerParam.SAMPLE_VALUE_MAX * amp2);
    }

    private void SleepWithoutInterrupt(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private double baseTimeMs = SystemClock.uptimeMillis();

    private void LimitFrameRate(double updateMs) {
        // Limit the frame rate by wait `delay' ms.
        baseTimeMs += updateMs;
        long delay = (int) (baseTimeMs - SystemClock.uptimeMillis());
//      Log.i(TAG, "delay = " + delay);
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Log.i(TAG, "Sleep interrupted");  // seems never reached
            }
        } else {
            baseTimeMs -= delay;  // get current time
            // Log.i(TAG, "time: cmp t="+Long.toString(SystemClock.uptimeMillis())
            //            + " v.s. t'=" + Long.toString(baseTimeMs));
        }
    }

    private double[] mdata;

    // Generate test data.
    private int readTestData(short[] a, int offsetInShorts, int sizeInShorts, int id) {
        if (mdata == null || mdata.length != sizeInShorts) {
            mdata = new double[sizeInShorts];
        }
        Arrays.fill(mdata, 0.0);
        switch (id - 1000) {
            case 1:
                sineGen2.getSamples(mdata);
                // No break, so values of mdata added.
            case 0:
                sineGen1.addSamples(mdata);
                for (int i = 0; i < sizeInShorts; i++) {
                    a[offsetInShorts + i] = (short) Math.round(mdata[i]);
                }
                break;
            case 2:
                for (int i = 0; i < sizeInShorts; i++) {
                    a[i] = (short) (analyzerParam.SAMPLE_VALUE_MAX * (2.0*Math.random() - 1));
                }
                break;
            default:
                Log.w(TAG, "readTestData(): No this source id = " + analyzerParam.audioSourceId);
        }
        // Block this thread, so that behave as if read from real device.
        LimitFrameRate(1000.0*sizeInShorts / analyzerParam.sampleRate);
        return sizeInShorts;
    }


    @Override
    public void run() {
        AudioRecord record;

        long tStart = SystemClock.uptimeMillis();
        try {
            activity.graphInit.join();  // TODO: Seems not working as intended....
        } catch (InterruptedException e) {
            Log.w(TAG, "run(): activity.graphInit.join() failed.");
        }
        long tEnd = SystemClock.uptimeMillis();
        if (tEnd - tStart < 500) {
            Log.i(TAG, "wait more.." + (500 - (tEnd - tStart)) + " ms");
            // Wait until previous instance of AudioRecord fully released.
            SleepWithoutInterrupt(500 - (tEnd - tStart));
        }

        int minBytes = AudioRecord.getMinBufferSize(analyzerParam.sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBytes == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "SamplingLoop::run(): Invalid AudioRecord parameter.\n");
            return;
        }

        /*
          Develop -> Reference -> AudioRecord
             Data should be read from the audio hardware in chunks of sizes
             inferior to the total recording buffer size.
         */
        // Determine size of buffers for AudioRecord and AudioRecord::read()
        int readChunkSize = analyzerParam.hopLen;  // Every hopLen one fft result (overlapped analyze window)
        readChunkSize = Math.min(readChunkSize, 2048);  // read in a smaller chunk, hopefully smaller delay
        int bufferSampleSize = Math.max(minBytes / analyzerParam.BYTE_OF_SAMPLE, analyzerParam.fftLen/2) * 2;
        // tolerate up to about 1 sec.
        bufferSampleSize = (int)Math.ceil(1.0 * analyzerParam.sampleRate / bufferSampleSize) * bufferSampleSize;

        // Use the mic with AGC turned off. e.g. VOICE_RECOGNITION for measurement
        // The buffer size here seems not relate to the delay.
        // So choose a larger size (~1sec) so that overrun is unlikely.
        try {
            if (analyzerParam.audioSourceId < 1000) {
                record = new AudioRecord(analyzerParam.audioSourceId, analyzerParam.sampleRate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, analyzerParam.BYTE_OF_SAMPLE * bufferSampleSize);
            } else {
                record = new AudioRecord(analyzerParam.RECORDER_AGC_OFF, analyzerParam.sampleRate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, analyzerParam.BYTE_OF_SAMPLE * bufferSampleSize);
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Fail to initialize recorder.");
            activity.analyzerViews.notifyToast("Illegal recorder argument. (change source)");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            // Check Auto-Gain-Control status.
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl agc = AutomaticGainControl.create(
                        record.getAudioSessionId());
                if (agc.getEnabled())
                    Log.i(TAG, "SamplingLoop::Run(): AGC (automatic gain control): enabled.");
                else
                    Log.i(TAG, "SamplingLoop::Run(): AGC (automatic gain control): disabled.");
            } else {
                Log.i(TAG, "SamplingLoop::Run(): AGC (automatic gain control): not available.");
            }
        }

        Log.i(TAG, "SamplingLoop::Run(): Starting recorder... \n" +
                "  source          : " + analyzerParam.getAudioSourceName() + "\n" +
                String.format("  sample rate     : %d Hz (request %d Hz)\n", record.getSampleRate(), analyzerParam.sampleRate) +
                String.format("  min buffer size : %d samples, %d Bytes\n", minBytes / analyzerParam.BYTE_OF_SAMPLE, minBytes) +
                String.format("  buffer size     : %d samples, %d Bytes\n", bufferSampleSize, analyzerParam.BYTE_OF_SAMPLE*bufferSampleSize) +
                String.format("  read chunk size : %d samples, %d Bytes\n", readChunkSize, analyzerParam.BYTE_OF_SAMPLE* readChunkSize) +
                String.format("  FFT length      : %d\n", analyzerParam.fftLen) +
                String.format("  nFFTAverage     : %d\n", analyzerParam.nFFTAverage));
        analyzerParam.sampleRate = record.getSampleRate();

        if (record.getState() == AudioRecord.STATE_UNINITIALIZED) {
            Log.e(TAG, "SamplingLoop::run(): Fail to initialize AudioRecord()");
            activity.analyzerViews.notifyToast("Fail to initialize recorder.");
            // If failed somehow, leave user a chance to change preference.
            return;
        }

        short[] audioSamples = new short[readChunkSize];
        int numOfReadShort;
        /**************AV Declarations Start*******************************************************/
        //create a buffer for data for the last 1.5 sec
        final int DATACHUNKS_TOSAVE_AFTERDETECTION  = ((WORDDURATION_AFTERDETECTION *analyzerParam.sampleRate) / readChunkSize)/1000;
        final int DATACHUNKS_TOSAVE_BEFOREDETECTION = ((WORDDURATION_BEFOREDETECTION*analyzerParam.sampleRate) / readChunkSize)/1000;
        final int DATACHUNKS_TOSAVE_TOTAL=DATACHUNKS_TOSAVE_BEFOREDETECTION + DATACHUNKS_TOSAVE_AFTERDETECTION;//shall be 15 @8000 samples per sec and  23 @16000 samples per second
        //Log.i(TAG, "DATACHUNKS_TOSAVE_TOTAL=" + DATACHUNKS_TOSAVE_TOTAL);
        short[][]  tempBuffers = new short[DATACHUNKS_TOSAVE_TOTAL][readChunkSize];//last 15 buffers (1.5sec) will be recorded here. When there was a vocalization, I will dump this buffers into a file
        int tempBuffersIndex = 0;
        int nDataChunksToWait=0;

        //create a buffer for threshold for the last 1 sec
        final int NUMBUFFHIST=((THRESHOLDBUFFERDURATION*analyzerParam.sampleRate) / readChunkSize)/1000;;//every buffer is approximately 100ms
        rmsHistory =  new double[NUMBUFFHIST]; for(int i=0; i<NUMBUFFHIST; i++) rmsHistory[i]=0;//remember means of 9 previous buffers. This is my amplitude threshold
        Log.i(TAG, "NUMBUFFHIST=" + NUMBUFFHIST);
        //for(int i=0; i<200; i++) classIndicator.append("-");//this is used to indicate each chunk's classification for the presence of vocalization
        /**************AV Declarations End*******************************************************/

        stft = new STFT(analyzerParam);
        stft.setAWeighting(analyzerParam.isAWeighting);
        if (spectrumDBcopy == null || spectrumDBcopy.length != analyzerParam.fftLen/2+1) {
            spectrumDBcopy = new double[analyzerParam.fftLen/2+1];
        }

        RecorderMonitor recorderMonitor = new RecorderMonitor(analyzerParam.sampleRate, bufferSampleSize, "SamplingLoop::run()");
        recorderMonitor.start();

        WavWriter wavWriter = new WavWriter(analyzerParam.sampleRate);

        try {record.startRecording();}// Start recording
        catch (IllegalStateException e) {
            Log.e(TAG, "Fail to start recording.");
            activity.analyzerViews.notifyToast("Fail to start recording.");
            return;
        }

        /**********************************************************************************************************/
        // Main loop: When running in this loop (including when paused), you can not change properties related to recorder: e.g. audioSourceId, sampleRate, bufferSampleSize
        while (isRunning) {
            // Read data
            if (analyzerParam.audioSourceId >= 1000) {  numOfReadShort = readTestData(audioSamples, 0, readChunkSize, analyzerParam.audioSourceId);}
            else {                                      numOfReadShort = record.read( audioSamples, 0, readChunkSize);}   // pulling

            System.arraycopy(audioSamples, 0, tempBuffers[tempBuffersIndex], 0, readChunkSize);//keep the last 1.5 seconds in memory
            tempBuffersIndex++; if(tempBuffersIndex>=DATACHUNKS_TOSAVE_TOTAL) tempBuffersIndex=0;

            if ( recorderMonitor.updateState(numOfReadShort) ) {  // performed a check
                if (recorderMonitor.getLastCheckOverrun()) activity.analyzerViews.notifyOverrun();
            }

            if(nDataChunksToWait>0)
            {//keep recording for 1 second after a vocalization was detected
                nDataChunksToWait--;//decrement
                if (nDataChunksToWait==0)
                {//all buffers have been filled => push data into the wav file
                    wavWriter.start();
                    wavSecRemain = wavWriter.secondsLeft();//check for available computer memory
                    Log.i(TAG, "PCM write to file " + wavWriter.getPath());
                    for(int i=0; i<DATACHUNKS_TOSAVE_TOTAL; i++){  wavWriter.pushAudioShort(tempBuffers[(tempBuffersIndex+i)%DATACHUNKS_TOSAVE_TOTAL], readChunkSize);}
                    wavSec = wavWriter.secondsWritten();
                    activity.analyzerViews.updateRec(wavSec);
                    wavWriter.stop(); Log.i(TAG, "SamplingLoop::Run(): Writing an end to the saved wav.");
                    activity.analyzerViews.notifyWAVSaved(wavWriter.relativeDir);

                    /********************************The Wav file is ready => Send API to SpeechAce************************************************************/
                    //The Wav file is ready => Send API to SpeechAce
                    /********************************************************************************************/
                }
            }

            if (isPaused) {continue;}

            stft.feedData(audioSamples, numOfReadShort);

            // If there is new spectrum data, do plot
            if (stft.nElemSpectrumAmp() >= analyzerParam.nFFTAverage)
            {
                // Update spectrum or spectrogram
                final double[] spectrumDB = stft.getSpectrumAmpDB();
                System.arraycopy(spectrumDB, 0, spectrumDBcopy, 0, spectrumDB.length);
                activity.analyzerViews.update(spectrumDBcopy);
//              fpsCounter.inc();

                stft.calculatePeak();
                activity.maxAmpFreq = stft.maxAmpFreq;
                activity.maxAmpDB = stft.maxAmpDB;

                // get RMS
                activity.dtRMS = stft.getRMS();
                activity.dtRMSFromFT = stft.getRMSFromFT(300);//RMS shall not include low frequency noise. Include only frequency where we can find vocalizations, i.e. above 300Hz


                /**************AV Playground Start*******************************************************/
                nBuffers++; if(nBuffers==Integer.MAX_VALUE) nBuffers=NUMBUFFHIST;

                //1. remember the RMS of last 8 buffers in order to generate a threshold. The current buffer is rmsHistory[0]; the oldest buffer is rmsHistory[NUMBUFFHIST-1]
                for(int i=(NUMBUFFHIST-1); i>0; i--) rmsHistory[i]=rmsHistory[i-1];//remember the RMS of last 8 buffers
                rmsHistory[0] = activity.dtRMSFromFT;//the current buffer Math.round(mean);

                //2. determine the threshold for this buffer
                double ampThreshold=0;
                for(int i=0; i<NUMBUFFHIST-1; i++) ampThreshold+=rmsHistory[i];//do not include the current buffer
                ampThreshold/=(NUMBUFFHIST-1);//classIndicator = "ampThreshold=" + String.valueOf(ampThreshold) + "; activity.dtRMSFromFT=" + String.valueOf(activity.dtRMSFromFT);

                //3. Identify and describe harmonics in freq domain
                //ToDo: consider more harmonics and dynamically identified regions: so the 1st peak is identified then the 2nd peak to the right, then the peak to the left. That will entail identifying local minima.
                int freqOfPeak1=stft.getIndexOfPeakFreq(200, 800); //classIndicator = "maxFreq=" + String.valueOf(maxFreq);//Log.i(TAG,String.valueOf(spectrumDB[100])+"; "+String.valueOf(spectrumDB[101])+"; "+String.valueOf(spectrumDB[102])+"; "+String.valueOf(spectrumDB[103])+"; "+String.valueOf(spectrumDB[104])+"; "+String.valueOf(spectrumDB[105]));
                int freqOfPeak2=stft.getIndexOfPeakFreq(800, 1800);
                int freqOfPeak3=stft.getIndexOfPeakFreq(1800,3500);
                double mean = stft.getMeanPSD_dB(300,3500);
                double max1 = stft.getPeakAmpl_dB(freqOfPeak1);
                double max2 = stft.getPeakAmpl_dB(freqOfPeak2);
                double max3 = stft.getPeakAmpl_dB(freqOfPeak3);
                double ratio1 = stft.getPeakToMeanRatio(freqOfPeak1, 120);//1. The ratio tells me how high the harmonic stands above background LOCALLY.
                double ratio2 = stft.getPeakToMeanRatio(freqOfPeak2, 500);
                double ratio3 = stft.getPeakToMeanRatio(freqOfPeak3, 500);

                //4. i'd like to make sure that each harmonic is not some kind of not-audible digital artifact. For that I am using absolute values in dB.
                boolean bP1, bP2, bP3; bP1=bP2=bP3=false;//markers for the three peaks standing above the background;
                double minPeakPower=mean+20.0;//min peak power in dB
                if(max1>minPeakPower) bP1=true;
                if(max2>minPeakPower) bP2=true;
                if(max3>minPeakPower) bP3=true;

                //5. Weighted ratio: use only harmonics with minimum power. We really need to remove meaningless ratios that do not represent any sound, these are basically just artifacts
                double weightedRatio=0;
                if		(bP1 && bP2 && bP3) weightedRatio=(ratio1+ratio2+ratio3)/3;
                else if (bP2 && bP3) weightedRatio=(ratio2+ratio3)/2;
                else if (bP1 && bP3) weightedRatio=(ratio1+ratio3)/2;
                else if (bP1 && bP2) weightedRatio=(ratio1+ratio2)/2;
                else if (bP3) weightedRatio=ratio3;
                else if (bP2) weightedRatio=ratio2;
                else if (bP1) weightedRatio=ratio1;
                else weightedRatio=-1;//'m' - none of the peaks stand above background.

                //6. Assign the classifier for this buffer based on weightedRatio
                String bC="";//string to hold the classifier
                if(weightedRatio==-1) bC="m";//'m' - none of the peaks stand above background.
                else if(!bP2 && !bP3 && freqOfPeak1<295) bC="f";//freq of the only peak is too low
                else if (weightedRatio>10)bC="a";//vocalization was detected
                else if (weightedRatio>9 )bC="k";//'k' for candidate
                else if (weightedRatio>6 )bC="e";//'e' for candidate who almost did it., i.e. 70% of min_ratio.
                else    bC="~"; //~ for ratio too small to become a candidate

                //7. if the buffer contains loud noise that crosses threshold AND has some reasonable harmonics=>mark as 'w'=buffer with vocalization.
                if( nBuffers>=NUMBUFFHIST && activity.dtRMSFromFT>(4*ampThreshold) && (bC=="k" || bC=="e") ) bC="t";//Threshold crossing //classIndicator="Crossed Threshold"; else classIndicator="Not crossed threshold";

                //8. Find the beginning of the vocalization as the fist buffer that crossed the threshold - this approach is NOT USED. We simply always grabe 600ms before the data chunk with vocalization
                if(bC=="a" || bC=="t")
                {//this buffer is definitely part of a vocalization => wait for DATACHUNKS_TOSAVE_AFTERDETECTION buffers (1sec), then dump the tempAudioSample into a file
                    if(nDataChunksToWait==0){ nDataChunksToWait = DATACHUNKS_TOSAVE_AFTERDETECTION; Log.i(TAG, "I have detected a word. I will wait for 1sec (DATACHUNKS_TOSAVE_AFTERDETECTION) and save a wav file");}//this is the marker for vocalization. It is also a counter of buffers to wait
                    else{ Log.i(TAG, "I have detected a vocalization in this data chink, but I am saving this data chink as part of a previous word and therefore I will NOT restart buffering.");}
                    /*classIndicator.insert(0,"w");//this buffer is part of vocalization //report each data chunk classification with classIndicator that will look like this: wwwww~~~~~mmfee~wwwww
                    if (rmsHistory[0] >= ampThreshold) {//if there was noise before this buffer count that noise as beginning of a word
                        if      (nBuffers >= 4 && rmsHistory[3] > ampThreshold) {classIndicator.setCharAt(4, 'w');classIndicator.setCharAt(3, 'w');classIndicator.setCharAt(2, 'w');classIndicator.setCharAt(1, 'w');}//look as far away as four buffers=> this is part of the same word
                        else if (nBuffers >= 3 && rmsHistory[2] > ampThreshold) {classIndicator.setCharAt(3, 'w');classIndicator.setCharAt(2, 'w');classIndicator.setCharAt(1, 'w');}//look as far away as three buffers=> this is part of the same word
                        else if (nBuffers >= 2 && rmsHistory[1] > ampThreshold) {classIndicator.setCharAt(2, 'w');classIndicator.setCharAt(1, 'w');}
                        else if (nBuffers >= 1) {classIndicator.setCharAt(1, 'w');}//add one buffer on the edge
                    }*/
                }
                //else classIndicator.insert(0,"-");;

                //9. Report the classification of the current buffer //Consider reporting peak thickness
                if(classIndicator.length()>200) classIndicator.deleteCharAt(200);
                classIndicator.insert(0,bC);
                if(bReportAnalysis) {Log.i(TAG, classIndicator.toString());}
                if(bReportAnalysis) {
                    Log.i(TAG, bC + "; wR=" + String.valueOf(Math.round(weightedRatio)) +
                            "; f1=" + String.valueOf(freqOfPeak1) + "; m1=" + String.valueOf(Math.round(max1)) + "dB; r1=" + String.valueOf(Math.round(ratio1)) +
                            "; f2=" + String.valueOf(freqOfPeak2) + "; m2=" + String.valueOf(Math.round(max2)) + "dB; r2=" + String.valueOf(Math.round(ratio2)) +
                            "; f3=" + String.valueOf(freqOfPeak3) + "; m3=" + String.valueOf(Math.round(max3)) + "dB; r3=" + String.valueOf(Math.round(ratio3)) +
                            "; mean=" + String.valueOf(Math.round(mean)) + "dB");
                }

                //10. Push the last 1.5 sec into a file
                //See above: if(nDataChunksToWait>0)

                /**************AV Playground End*******************************************************/
            }
        }
        Log.i(TAG, "SamplingLoop::Run(): Actual sample rate: " + recorderMonitor.getSampleRate());
        Log.i(TAG, "SamplingLoop::Run(): Stopping and releasing recorder.");
        record.stop();
        record.release();
    }

    void setAWeighting(boolean isAWeighting) {
        if (stft != null) {
            stft.setAWeighting(isAWeighting);
        }
    }

    void setPause(boolean pause) {
        this.isPaused = pause;
    }

    boolean getPause() {
        return this.isPaused;
    }

    void finish() {
        isRunning = false;
        interrupt();
    }
}
