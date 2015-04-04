package de.tum.mw.lfe.radio;

//------------------------------------------------------
//Revision History 'LfE radio task implementation'
//------------------------------------------------------
//Version	Date			Author				Mod
//1		    Mar, 2015		Michael Krause		initial
//1.1		Apr, 2015		Michael Krause		removed bug; removeGlobalOnLayoutListener crashed
//
//------------------------------------------------------

/*
        The MIT License (MIT)

        Copyright (c) 2015 Michael Krause (krause@tum.de), Institute of Ergonomics, Technische Universität München

        Permission is hereby granted, free of charge, to any person obtaining a copy
        of this software and associated documentation files (the "Software"), to deal
        in the Software without restriction, including without limitation the rights
        to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
        copies of the Software, and to permit persons to whom the Software is
        furnished to do so, subject to the following conditions:

        The above copyright notice and this permission notice shall be included in
        all copies or substantial portions of the Software.

        THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
        IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
        FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
        AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
        LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
        OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
        THE SOFTWARE.
*/


import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.PowerManager;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetFileDescriptor;
import android.provider.Settings;
import android.text.Html;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class RadioActivity extends Activity implements View.OnTouchListener{

	private static final String TAG = "LFEradio.Activity";
	private StateMachine mStateMachine;//state machine for radio function
	private PowerManager.WakeLock mWakeLock = null;
	private ProgressDialog pd = null;
	private MyBackgroundTask myBackgroundTask;
	//private Handler mHandler = new Handler();
	
	//volume is not managed by state machine!
	private int mVolBeforeMute = 0;
	private boolean mIsMuted = false;

    //save user's brightness and mode
    int mSavedBrightness=128;
    int mSavedBrightnessMode;

	
	//logging
	private File mFile=null;
	public static final String CSV_DELIMITER = ";"; //delimiter within csv
	public static final String CSV_LINE_END = "\r\n"; //line end in csv
	public static final String FOLDER = "RadioTask"; //folder
	public static final String FOLDER_DATE_STR = "yyyy-MM-dd";//logging folder format
	public static final String FILE_EXT = ".txt";
	public static final String HEADER ="timestamp;action;volume;mode;currentBand;desiredBand;currentFreq;desiredFreq;freqStep;";
	
	//possible actions: appResume; appPause; touchedBackground; finishedTask; resetTask; setModeCd; setModeRadio; switchBand; volUp; volDown; freqUp; freqDown; mute; touchedBackground;  
	
	//----------------------------------------------------------------------------
	//we also handle volume up/down hardware button right
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
        	refreshVolumeDisplay();
        	if (mIsMuted){
        		AudioManager audioManager =  (AudioManager)getSystemService(Context.AUDIO_SERVICE);
        		int vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        		if (vol> 0) mIsMuted = false;

        	}
        }
    };
	//----------------------------------------------------------------------------
	//progress dialog on start up
	class MyBackgroundTask extends AsyncTask<String, Integer, Boolean> {
		private RadioActivity parent;
		
		MyBackgroundTask(RadioActivity p){
			parent = p;
		}
		
		@Override
		protected void onPreExecute() {
		}
		
		@Override
		protected Boolean doInBackground(String... params) {
		
			parent.mStateMachine.initSounds();
			
		    return true;
		
		}		
		
		@Override
		protected void onPostExecute(Boolean result) {
		
		    if (parent.pd != null) {
		    	parent.pd.dismiss();
		    }
		
		    setContentView(R.layout.activity_main);


            LinearLayout l = (LinearLayout)findViewById(R.id.LinearLayoutMain);
            final ViewTreeObserver observer= l.getViewTreeObserver();
            observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    registerButtonDownListenerAndCheckSize();
                    //observer.removeGlobalOnLayoutListener(this); //V1.0 crashed on some devices; debugged next line V1.1
                    ((LinearLayout)findViewById(R.id.LinearLayoutMain)).getViewTreeObserver().removeGlobalOnLayoutListener(this);
                }
            });

		    
		    mStateMachine.reset();
			logging("newTask");
		
		}
	}	
	
	//----------------------------------------------------------------------------
	//state machine to manage radio freqs and tuning task
	private class StateMachine implements MediaPlayer.OnPreparedListener {
		
		private RadioActivity mParent;//parent activity
		private MediaPlayer mMediaPlayer = new MediaPlayer();
		private float mStationSoundVolume = 0;
		private SoundPool mSoundPoolNoise;
		private SoundPool mSoundPoolBeep;
		//sound IDs for beep, noise and stations
		private int mSoundIdBeep;
		private int mSoundIdNoise;
		private int mStreamIdNoise;	
		private boolean mSoundNoiseIsPlaying = false;
		private float mNoiseAmplitude;
		
		private Handler mHandler = new Handler();
		

        //For legal reasons we are not allowed to upload the (foreign) music sound files to github or re-license the files
        //For our own voice files we decided to take the same way (they are not in this GPL github repository)
        //
        //If you want to program, you may find within 10 seconds how to get the sound files out of an Android apk
        //So, you can develop, contribute to the project and setup own experiments.
        //
        //If you fork and would make an own public app, you have to get other sound files or solutions.

		public final int STATION_RESSOURCES[] = {
	        R.raw.voice_andreas_nby,
	        R.raw.voice_antonia_us,
	        R.raw.voice_asuman_tr,
	        R.raw.voice_bastiaan_nl,
	        R.raw.voice_jian_cn,
	        R.raw.voice_joel_pt,
	        R.raw.voice_magnus_sw,
	        R.raw.voice_olena_ru,
	        R.raw.voice_severina_bg,
	        R.raw.voice_wahib_ur,			
	        R.raw.music_loop_cruising,
	        R.raw.music_loop_dance_zone,
	        R.raw.music_loop_diving_turtle,
	        R.raw.music_loop_fast_track,
	        R.raw.music_loop_memories,
	        R.raw.music_loop_on_the_run,
	        R.raw.music_loop_oriental_drift,
	        R.raw.music_loop_solution,
	        R.raw.music_loop_techno_dog,
	        R.raw.music_loop_urban_spy	
		};
		
		int mCurrentMode;//CD or Radio
	 		public static final int  STALL = -1;//we stall during ">>OK<<" on display
	 		public static final int  CD = 0;
		 	public static final int  RADIO = 1;
		   
		int mCurrentBand;
		   public static final int  AM = 0;
		   public static final int  FM1 = 1;
		   public static final int  FM2 = 2;
		   public static final int  WEATHER = 3;
		
		public final String[] BAND_NAMES = {"AM", "FM1", "FM2", "Weather"};
		public final  String[] FREQ_RANGE_UNIT = {"kHz", "MHz", "MHz", "MHz"};
		public final float FREQ_STEP[] = {5, 0.1f, 0.1f, 0.025f};
		public final float  MIN_FREQ[] = {540, 89, 89, 162.400f};
		public final float MAX_FREQ[] = {1610, 108, 108, 162.550f};

		private float[] mCurrentFreq = new float[BAND_NAMES.length];
		private int mDesiredBand=0; //which band should be tuned
		private float mDesiredFreq=0; //which freq should be tuned
		
		private int   mStationsPermutations[][] = new int[3][STATION_RESSOURCES.length]; //permutation of stations for three bands (AM,FM1, FM2)  
		private float mStationsFreqs[][] = new float[3][STATION_RESSOURCES.length]; //random station frequencies for three bands (AM,FM1, FM2)  
		private int mStationOld = Integer.MIN_VALUE;

		
		
		public StateMachine(RadioActivity p){
			mParent = p;
			mMediaPlayer.setOnPreparedListener(this);
			reset();
		}
		
		
		//async media player prepared
		public void onPrepared (MediaPlayer mp){
			try{
				mMediaPlayer.setLooping(true);
				mMediaPlayer.setVolume(mStationSoundVolume, mStationSoundVolume);
				mMediaPlayer.start();
			}catch(Exception e){}
		}
		
		public void playBeep(){
	        if (mSoundPoolBeep != null) mSoundPoolBeep.play(mSoundIdBeep, 0.2f, 0.2f, 1, 0, 1);
		}
		
		public void initSounds(){
			mSoundPoolBeep = new SoundPool(1, AudioManager.STREAM_MUSIC, 0);
			mSoundPoolNoise = new SoundPool(1, AudioManager.STREAM_MUSIC, 0);
	        
	        mSoundIdBeep  = mSoundPoolBeep.load(mParent, R.raw.beep, 1);
	        mSoundIdNoise = mSoundPoolNoise.load(mParent, R.raw.uen, 1);
	      
		}
		
		public void release(){
			if (mMediaPlayer != null) mMediaPlayer.release();
			mMediaPlayer = null;
			
	        if (mSoundPoolBeep != null) mSoundPoolBeep.release();
	        mSoundPoolBeep = null;
			
	        if (mSoundPoolNoise != null) mSoundPoolNoise.release();
	        mSoundPoolNoise = null;
			
		}
		
		public void stall(){

			mCurrentMode = STALL;	
			
		}
		
		public void setModeCd(){

			mCurrentMode = CD;	
			
			mStationOld = Integer.MIN_VALUE;//ensure that startStationSound() will work
			
			refresh();
		}
		
		public void setModeRadio(){

			if (mCurrentMode == STALL) return;//we dont allow switching to radio mode during stall
			
			mCurrentMode = RADIO;
			
			refresh();
		}
		
		
		public void decFreq(){
			if (mCurrentMode != RADIO) return;
			
			if ((mCurrentFreq[mCurrentBand]-FREQ_STEP[mCurrentBand]) >= MIN_FREQ[mCurrentBand])
				mCurrentFreq[mCurrentBand] -= FREQ_STEP[mCurrentBand];
			
			refresh();
		}		
		
		public void incFreq(){
			if (mCurrentMode != RADIO) return;
			
			if ((mCurrentFreq[mCurrentBand]+ FREQ_STEP[mCurrentBand]) <= MAX_FREQ[mCurrentBand])
				mCurrentFreq[mCurrentBand] += FREQ_STEP[mCurrentBand];
			
			refresh();
		}
		

		void silent(){
			if (mMediaPlayer.isPlaying()) mMediaPlayer.stop();
			try{mSoundPoolNoise.pause(mStreamIdNoise);}catch (Exception e){}
			mSoundNoiseIsPlaying = false;
			mNoiseAmplitude = 0;
		}
		
	    private Runnable loopWorkaround = new Runnable() {//workAround for Android 4.3
			@Override
			public void run() {
				mHandler.removeCallbacks(this);
				if (mSoundPoolNoise != null){
					mSoundPoolNoise.stop(mStreamIdNoise);
					mStreamIdNoise = mSoundPoolNoise.play(mSoundIdNoise, 1, 1, 99, 0, 1);
					mSoundPoolNoise.setVolume(mStreamIdNoise, mNoiseAmplitude, mNoiseAmplitude);
					mHandler.postDelayed(loopWorkaround,1200);
				}	
			} 			    	
	    };	
		
		void refresh(){//refresh display and sound
			
			//---refresh instruction
			String task = "Tune ";
            switch (mDesiredBand) {
            case AM:
            	task += BAND_NAMES[mDesiredBand] + " " + String.format("%d",(int)mDesiredFreq) + " " + FREQ_RANGE_UNIT[mDesiredBand];
                break;
            case FM1:
            case FM2:
                task += BAND_NAMES[mDesiredBand] + " " + String.format("%.1f",mDesiredFreq) + " " + FREQ_RANGE_UNIT[mDesiredBand];
                break;
            case WEATHER:
            	task += BAND_NAMES[mCurrentBand] + " " + String.format("%.3f",mDesiredFreq) + " " + FREQ_RANGE_UNIT[mDesiredBand];
                break;
            default: 
            	task += "---";
                break;
            }				
			
            setInstruction(task);

			
            
            
			if (mCurrentMode == CD){
				setDisplay("CD PLAYING\n");
				silent();
			}
			
			if (mCurrentMode == RADIO){
				if (!mSoundNoiseIsPlaying){
					try{
						mStreamIdNoise = mSoundPoolNoise.play(mSoundIdNoise, 1, 1, 99, -1, 1);
						mSoundNoiseIsPlaying = true;
						if (android.os.Build.VERSION.RELEASE.equals("4.3")){mHandler.postDelayed(loopWorkaround,1000);}//Android 4.3 has a well known bug in SoundPool and does not loop; so we do an ugly workaround
					}catch (Exception e){}
				}
				
				//e.g. AM 550 kHz
				String temp;
	            switch (mCurrentBand) {
	            case AM:
					 temp = BAND_NAMES[mCurrentBand] + "\n" + String.format("%d",(int)mCurrentFreq[mCurrentBand]) + " " + FREQ_RANGE_UNIT[mCurrentBand];
                     break;
	            case FM1:
	            case FM2:
				 	temp = BAND_NAMES[mCurrentBand] + "\n" + String.format("%.1f",mCurrentFreq[mCurrentBand]) + " " + FREQ_RANGE_UNIT[mCurrentBand];
                    break;
	            case WEATHER:
				 	temp = BAND_NAMES[mCurrentBand] + "\n" + String.format("%.3f",mCurrentFreq[mCurrentBand]) + " " + FREQ_RANGE_UNIT[mCurrentBand];
                    break;
	            default: 
	            	temp = "---";
	                break;
	            }				
				
				setDisplay(temp);
				

				int indexBelow = 0;
				int indexAbove = STATION_RESSOURCES.length-1;
				
				if (mCurrentBand < (BAND_NAMES.length-1)){
					//find stations nearest above and below
					for(int i=0;i < STATION_RESSOURCES.length; i++){
						if (mCurrentFreq[mCurrentBand] >= mStationsFreqs[mCurrentBand][i])
						{
							indexBelow = i;
							if (i < (STATION_RESSOURCES.length-1)) indexAbove = i+1;
							else indexAbove = i;
						}else{
							break;
						}
					}//for
				}else{
					//on weather channel only noise
					if (mMediaPlayer.isPlaying()) mMediaPlayer.stop();
					mNoiseAmplitude = 1;
					mSoundPoolNoise.setVolume(mStreamIdNoise, mNoiseAmplitude, mNoiseAmplitude);
					return;
				}
				
				float freqBelow = mStationsFreqs[mCurrentBand][indexBelow];
				float freqAbove = mStationsFreqs[mCurrentBand][indexAbove];
				
				//set noise level
				float freqDistance = freqAbove - freqBelow + 0.0001f;//distance between station above and below; 0.0001f prevent div0
				float curDiff = mCurrentFreq[mCurrentBand] - freqBelow;	
				//we approximate noise shaping from AAM p.49 by a sin function		
				mNoiseAmplitude = (float) Math.sin((Math.PI * curDiff) /freqDistance);
				mSoundPoolNoise.setVolume(mStreamIdNoise, mNoiseAmplitude, mNoiseAmplitude);
				

				mStationSoundVolume = (1-mNoiseAmplitude)*0.7f;
				if (mMediaPlayer.isPlaying()) mMediaPlayer.setVolume(mStationSoundVolume, mStationSoundVolume);	
				
				//closer to above or below station?
				//start the sound of this station
				if (curDiff < (freqDistance/2)){
					//station above
					startStationSound(mStationsPermutations[mCurrentBand][indexBelow]);
				}else{// station below
					startStationSound(mStationsPermutations[mCurrentBand][indexAbove]);					
				}
				
				
			}//if currentMode == RADIO
			
			refreshVolumeDisplay();
			
			if 	( (Math.abs(mCurrentFreq[mCurrentBand] - mDesiredFreq) < 0.01 ) && (mCurrentBand == mDesiredBand) ){//user got the right band and freq
				mHandler.removeCallbacksAndMessages(null);//remove any pending msg
				mHandler.postDelayed(checkFinish,1000);//check in one second if it is still the right band and freq
			}

			
		}
		
		public void switchBand(){
			
			if (mCurrentMode != RADIO) return;
			
			if (mMediaPlayer.isPlaying()) mMediaPlayer.stop();
			
			mStationOld = Integer.MIN_VALUE;//reset; ensure that startStationSound() will work
			
			mCurrentBand++;
			if (mCurrentBand >= BAND_NAMES.length){
				mCurrentBand = 0;
			}
			
			refresh();
		}	
		
		
		void startStationSound(int station){
			if(mStationOld != station){

				try{
					mMediaPlayer.reset();
					AssetFileDescriptor afd = mParent.getResources().openRawResourceFd(STATION_RESSOURCES[station]);
					mMediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
					//mMediaPlayer.prepareAsync(); //would be nice; but application crashes sometimes with "PlayerDriver(95): Command (6) was cancelled" 
					//(Command 6 is Init). the init(6) is intercepted by an command 18 (cancel all commands) ?!
					mMediaPlayer.prepare();
					afd.close();
					mStationOld = station;
				}catch(Exception e){
					Log.e(TAG, "Error in startStationSound():"+e.getMessage());
				}
			}//if
		}
		
		
		void reset(){//reset for a new radio task
						
			mCurrentMode = CD;
			
			randomizeStations();
			
			//order important: first randomize stations than construct new tuning task
			
			randomTuningTask();
			
			refresh();			
			
		}
		
		public String getLogString(){
			 //"mode;currentBand;desiredBand;currentFreq;desiredFreq;freqStep;"
			 StringBuilder log = new StringBuilder(2048);
			 log.append(mCurrentMode);
			 log.append(CSV_DELIMITER);
			 log.append(mCurrentBand);
			 log.append(CSV_DELIMITER);
			 log.append(mDesiredBand);
			 log.append(CSV_DELIMITER);
			 log.append(String.format("%.3f",mCurrentFreq[mCurrentBand]));
			 log.append(CSV_DELIMITER);
			 log.append(String.format("%.3f",mDesiredFreq));
			 log.append(CSV_DELIMITER);
			 log.append(FREQ_STEP[mCurrentBand]);
			 		 				
			return log.toString();
		}
		
	    private Runnable checkFinish = new Runnable() {
			@Override
			public void run() {
				if 	( (Math.abs(mCurrentFreq[mCurrentBand] - mDesiredFreq) < 0.01 ) && (mCurrentBand == mDesiredBand)){//user has right band and freq
		
					logging("finishedTask");
					
					mStateMachine.stall();//we prevent altering the state machine until the reset
					
					setInstruction("");
					setDisplay(">>> OK <<<\n");
					silent();
					mHandler.removeCallbacksAndMessages(null);//remove any pending msg
					mHandler.postDelayed(delayedReset,1000);//next task in one sec					
				}
				
			} 			    	
	    };
	    
	    private Runnable delayedReset = new Runnable() {
			@Override
			public void run() {
				reset();//new task
				logging("newTask");
			} 			    	
	    };	    

		void randomTuningTask(){
			mDesiredBand = (int)(Math.random() * (BAND_NAMES.length-1));//get random band; weather band=4 is not included
			mDesiredFreq = mStationsFreqs[mDesiredBand][(int)(Math.random() * STATION_RESSOURCES.length)];//get random station freq
			
			mCurrentBand = mDesiredBand;
			
			//set randomly currentBand != desiredBand
			while(mCurrentBand == mDesiredBand){
				mCurrentBand  = (mCurrentBand + (int)(Math.random() * 100)) % BAND_NAMES.length;
			}
			

			//set weather channel in the middle
			mCurrentFreq[WEATHER] = (MIN_FREQ[WEATHER] + MAX_FREQ[WEATHER])/2; 

			//set bands to random frequencies, except weather channel
			for(int i=0; i<(BAND_NAMES.length-1); i++){
				
				if (i != mDesiredBand){
					mCurrentFreq[i] = MIN_FREQ[i] + FREQ_STEP[i] * (int)(Math.random() * ((MAX_FREQ[i]-MIN_FREQ[i]) /FREQ_STEP[i]) );
				}else{//for desired band we ensure that the user needs at least 40 steps to tune => 40 +rand() 
					int randomFrequencyStepsToTune = 40 +  (int)(Math.random() * 5 );// between 40 and 44
					int upOrDown = (int)(Math.random() * 2);//tune randomly up or down
					if (upOrDown == 0){//up
						mCurrentFreq[i] = mDesiredFreq + (randomFrequencyStepsToTune * FREQ_STEP[i]);
						if (mCurrentFreq[i] > MAX_FREQ[i]) mCurrentFreq[i] = mDesiredFreq - (randomFrequencyStepsToTune * FREQ_STEP[i]);//if bigger than max => tune down
					}else{//down
						mCurrentFreq[i] = mDesiredFreq - (randomFrequencyStepsToTune * FREQ_STEP[i]);
						if (mCurrentFreq[i] < MIN_FREQ[i]) mCurrentFreq[i] = mDesiredFreq + (randomFrequencyStepsToTune * FREQ_STEP[i]);//if lower than min => tune up
					}
					
				}//else
				
			}//for
		}
		
		
		void randomizeStations(){
			
			//get new permutation
			newPermutation(mStationsPermutations[AM]);
			newPermutation(mStationsPermutations[FM1]);
			newPermutation(mStationsPermutations[FM2]);
			
			//get new freqs
			newFreqDistribution(mStationsFreqs[AM], MIN_FREQ[AM], MAX_FREQ[AM], FREQ_STEP[AM]);
			newFreqDistribution(mStationsFreqs[FM1], MIN_FREQ[FM1], MAX_FREQ[FM1], FREQ_STEP[FM1]);
			newFreqDistribution(mStationsFreqs[FM2], MIN_FREQ[FM2], MAX_FREQ[FM2], FREQ_STEP[FM2]);
			
		}
		
		void newFreqDistribution(float freqArray[], float minFreq, float maxFreq, float freqStep){
			float range = maxFreq - minFreq;
			float stepsTotal = range / freqStep;
			float avgSteps = stepsTotal / STATION_RESSOURCES.length;//avg steps between stations
			
			//we want small random variations between 70%-130%
			float min = avgSteps * 0.7f;
			float max = avgSteps * 1.3f;
			int oldRandStep=0;
			
			freqArray[0] = minFreq;
			for(int i=1;i< STATION_RESSOURCES.length-1;i++){
				int randStep = (int)( min + ((int)(Math.random() * (max-min))) );
				
				//if odd we assign the random value
				//if even we assign the difference from the last to what we need, to get the average 
				
				if ((i % 2) == 1){
					freqArray[i] = freqArray[i-1] + (randStep * freqStep);
					//Log.i(TAG,"steps: "+ Integer.toString(randStep)+" freqArray["+ Integer.toString(i)+"] = "+Float.toString(freqArray[i]));
				}else{
					freqArray[i] = freqArray[i-1] + ((int)(2*avgSteps - oldRandStep) * freqStep);					
					//Log.i(TAG,"steps: "+ Integer.toString((int)(2*avgSteps - oldRandStep))+" freqArray["+ Integer.toString(i)+"] = "+Float.toString(freqArray[i]));
				}
				oldRandStep = randStep;
				
			}	
			freqArray[STATION_RESSOURCES.length-1] = maxFreq;
		}
		
		void newPermutation(int array[]){
			int temp[] = new int[STATION_RESSOURCES.length];
			//init tempArray
			for(int i=0;i< STATION_RESSOURCES.length;i++){
				temp[i] = i;
			}
			
			for(int i=0;i< STATION_RESSOURCES.length;i++){
				int rand = (int)(Math.random() * (STATION_RESSOURCES.length-i));
				array[i] = temp[rand];
				temp[rand] = temp[STATION_RESSOURCES.length-i-1];
			}	

		}
		
	}
	
	//----------------------------------------------------------------------------
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

        //no title
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        //full screen
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,  WindowManager.LayoutParams.FLAG_FULLSCREEN);

        //setContentView(R.layout.activity_main);
		
		
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
	}
/*
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}
*/	
	
	@Override
	public void onDestroy() {
        super.onDestroy();


	}
	

	@Override
	public void onResume() {
        super.onResume();

        Display display = getWindowManager().getDefaultDisplay();
        if (display.getHeight() > display.getWidth()) {//if portrait force landscape
            //setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            //to get reverse landscape, application must be hold in reverse landscape on start up
            toasting("On small devices, only landscape mode is valuable",Toast.LENGTH_LONG);
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction("android.media.VOLUME_CHANGED_ACTION");
        registerReceiver(mReceiver, filter);

        getWakeLock();


        mStateMachine = new StateMachine(this);

        myBackgroundTask = new MyBackgroundTask(this);
        pd = ProgressDialog.show(this, "LfE Radio Task", "Loading sounds. Please wait.", true, false);
        myBackgroundTask.execute();

        //the following is set by progress dialog:
        //mStateMachine.initSounds();
        //setContentView(R.layout.activity_main);
        //registerButtonDownListener();


        mFile = prepareLogging();
        
        logging("appResume");

        //save user settings
        try {
            mSavedBrightness = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            mSavedBrightnessMode = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE);
        }catch(Exception e){
            Log.e(TAG,"failed to get some system settings: "+e.getMessage());
        }

        //full light
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 255);
        //light automatic mode off
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);

        
	}	

	
	@Override
	public void onPause() {
        super.onPause();

        unregisterReceiver(mReceiver);

        logging("appPause");

        if (myBackgroundTask != null){
            myBackgroundTask.cancel(true);
            myBackgroundTask = null;
        }

        if (pd != null) pd.dismiss();
        pd = null;
        mStateMachine.release();
        mStateMachine = null;



        if(mWakeLock != null){
        	mWakeLock.release();
        	mWakeLock = null;
        }

        //restore users's brightness
        //full light
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, mSavedBrightness);
        //light automatic mode off
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, mSavedBrightnessMode);

        
	}	
	

	public void registerButtonDownListenerAndCheckSize(){
        //checkSize
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);


        LinearLayout l = (LinearLayout)findViewById(R.id.LinearLayoutMain);
        int px = l.getHeight();//we get the height from the app
        int resize = -1;
        if (px/4 < getResources().getDimensionPixelOffset(R.dimen.maxButtonHeight)){//if the max value from the dimension file is too large,
          resize = px/4;// we resize to height/4
        }


        //register for each used button action_down events, so button reacts on button_down
		int[] buttons = new int[10];
		buttons[0] = R.id.buttonBand;
		buttons[1] = R.id.buttonCD;
		buttons[2] = R.id.buttonMute;
		buttons[3] = R.id.buttonRadio;
		buttons[4] = R.id.buttonTuneDown;
		buttons[5] = R.id.buttonTuneUp;
		buttons[6] = R.id.buttonVolDown;
		buttons[7] = R.id.buttonVolUp;
		 
		for( int button: buttons )
		{
			Button b = (Button)findViewById(button);
			if (b != null){
				b.setOnTouchListener(this);

                if (resize > 0){

                    ViewGroup.LayoutParams params = b.getLayoutParams();
                    params.height = resize;
                    b.setLayoutParams(params);

                }
			}

		}
		
		//register also for background layout
		LinearLayout ll = (LinearLayout)findViewById(R.id.LinearLayout);
		ll.setOnTouchListener(this);
		
		
	}
	
	
	
	//implementation of onTouch for buttonDown events
    public boolean onTouch(View v, MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN ) {
        	
        	//dispatch
            switch (v.getId()) {
            case R.id.buttonBand:
            		 guiSwitchRadioBand(v);
                     break;
            case R.id.buttonCD:
            		 guiSwitchToCd(v);
                     break;
            case R.id.buttonMute:
            		 guiMute(v);
                     break;
            case R.id.buttonRadio:
            		 guiSwitchToRadio(v);
                     break;
            case R.id.buttonTuneDown:
            		 guiTuneDown(v);
                     break;
            case R.id.buttonTuneUp:
            		 guiTuneUp(v);
                     break;
            case R.id.buttonVolDown:
            		 guiVolumeDown(v);
                     break;
            case R.id.buttonVolUp:
            		 guiVolumeUp(v);
                     break;
            case R.id.LinearLayout:
       		 	//user hit the background, missed a button
            	logging("touchedBackground");
                break;
                     
            default: 
            	
                     break;
            }

        	
            return false;
        }
        return false;
    }	
	
	
	public void guiSwitchToCd(View v){
		mStateMachine.playBeep();
		mStateMachine.setModeCd();
		logging("setModeCd");
	}
	
	public void guiSwitchToRadio(View v){
		mStateMachine.playBeep();
		mStateMachine.setModeRadio();	
		logging("setModeRadio");
	}
	
	public void guiSwitchRadioBand(View v){
		mStateMachine.playBeep();
		mStateMachine.switchBand();
		
		logging("switchBand");
		
	}
	
	public void guiTuneUp(View v){
		mStateMachine.playBeep();
		mStateMachine.incFreq();
		
		logging("freqUp");		
	}

	public void guiTuneDown(View v){
		mStateMachine.playBeep();
		mStateMachine.decFreq();

		logging("freqDown");		
	}

	public void guiVolumeUp(View v){
		mStateMachine.playBeep();
		
		AudioManager audioManager =  (AudioManager)getSystemService(Context.AUDIO_SERVICE);
		int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
		int vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
		if (vol < max) vol++;
		audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,vol,0);
		
		if (mIsMuted){mIsMuted = false;}
		
		logging("volUp");

		refreshVolumeDisplay();
		
	}

	public void guiVolumeDown(View v){
		mStateMachine.playBeep();
		
		AudioManager audioManager =  (AudioManager)getSystemService(Context.AUDIO_SERVICE);
		int vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
		if (vol > 0) vol--;
		audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,vol,0);
		
		if (mIsMuted){mIsMuted = false;}
		
		logging("volDown");

		refreshVolumeDisplay();		
	}
	
	public void guiMute(View v){
		mStateMachine.playBeep();
		
		AudioManager audioManager =  (AudioManager)getSystemService(Context.AUDIO_SERVICE);
		if (mIsMuted){
			mIsMuted = false;
			audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, mVolBeforeMute,0);
		}else{
			mIsMuted = true;
			mVolBeforeMute = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
			audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
		}
		
		logging("muteButton");

		refreshVolumeDisplay();
	}

	public void guiShowAbout(View v){
		
	      String tempStr = "This is an open source implementation of a radio tuning task; e.g., for driver distraction studies. <br/>Procedure of one task: <br/> - Press radio button<br/> - Toggle to instructed radio band<br/> - Tune to the instructed frequency";
	      tempStr += "<br/><br/> (c) Michael Krause <a href=\"mailto:krause@tum.de\">krause@tum.de</a> <br/>2014 Institute of Ergonomics, TUM";
	      tempStr += "<br/><br/>More information on <br/><a href=\"http://www.lfe.mw.tum.de/radioTask\">http://www.lfe.mw.tum.de/radioTask</a>";
	      tempStr += "<br/><br/>Music by <a href=\"http://www.pacdv.com/sounds/\">www.pacdv.com/sounds</a>";
          tempStr += "<br/><br/>This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See MIT license for more details.";


        final SpannableString s = new SpannableString(Html.fromHtml(tempStr));
	      Linkify.addLinks(s, Linkify.EMAIL_ADDRESSES|Linkify.WEB_URLS);
	      
	      AlertDialog alert = new AlertDialog.Builder(this)
	          .setMessage( s )
		      .setTitle("LfE Radio Task "+getVersionString())
		      .setPositiveButton(android.R.string.ok,
		         new DialogInterface.OnClickListener() {
		         public void onClick(DialogInterface dialog, int whichButton){}
		         })
		       //exit app option. back button can be intentionally intercepted and disabled by onKey handling in order to not disturb an experiment
		      .setNegativeButton("Exit App",
		         new DialogInterface.OnClickListener() {
		         public void onClick(DialogInterface dialog, int whichButton){
		        	 finish();
		         }
		         })		         
		      .show();
		   
		   ((TextView)alert.findViewById(android.R.id.message)).setMovementMethod(LinkMovementMethod.getInstance()); 
	
	}
	
	public void resetButtonClick(View v){
		mStateMachine.reset();
		logging("reset");
	}
	
	
	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) { //handle external keys from bluetooth keyboard and volume keys

        if ((keyCode == KeyEvent.KEYCODE_BACK) && (event.getRepeatCount() == 0)) {//handle back key, comment out the following line and back button is blocked
            return super.onKeyDown(keyCode, event);//see also onKeyUp!!!
        }

		if (event.getRepeatCount() > 0) return true; //we only want single step, no repeat on key hold
		
		View foo = new View(getApplicationContext());//foo dummy view
		
    	logging("key: "+Integer.toString(keyCode));


        switch (keyCode) {//control radio task e.g. by bluetooth keyboard
	    	case KeyEvent.KEYCODE_VOLUME_DOWN:
	    		guiVolumeDown(foo);
	            return true;
	    	case KeyEvent.KEYCODE_VOLUME_UP:
	    		guiVolumeUp(foo);
	            return true;
	        case KeyEvent.KEYCODE_DPAD_LEFT:
	        	guiTuneDown(foo);
	            return true;
	        case KeyEvent.KEYCODE_DPAD_RIGHT:
	        	guiTuneUp(foo);
	            return true;
	        case KeyEvent.KEYCODE_SPACE:
	            guiSwitchRadioBand(foo);
	            return true;
	        case KeyEvent.KEYCODE_SHIFT_LEFT:
	        case KeyEvent.KEYCODE_SHIFT_RIGHT:
	        	guiSwitchToRadio(foo);
	            return true;
	        default:
	        	return true;//prevent that tabs, space and enter are delivered from bluetooth keyoard
	            //return super.onKeyDown(keyCode, event);
	    }
	}


	@Override
	public boolean onKeyUp(int keyCode, KeyEvent event) {

        if ((keyCode == KeyEvent.KEYCODE_BACK) && (event.getRepeatCount() == 0)) {//handle back key, comment out the following line and back button is blocked
            //toasting("back intentionally disabled",2000);
            return super.onKeyUp(keyCode, event);//see also onKeyDown!!!!
        }

        return true;//prevent that tabs, space and enter are deliveredfrom bluetooth keyoard
        //return super.onKeyUp(keyCode, event);
	}	
	
	@Override
	public boolean onKeyLongPress(int keyCode, KeyEvent event) {
		
    	return true;//prevent that tabs, space and enter are delivered from bluetooth keyoard
        //return super.onKeyUp(keyCode, event);
	}	
	
	@Override
	public boolean onKeyMultiple (int keyCode,  int count, KeyEvent event) {
		
    	return true;//prevent that tabs, space and enter are delivered from bluetooth keyoard
        //return super.onKeyUp(keyCode, event);
	}	
	
	
	public void setInstruction(String str){
 	    TextView instruction = null; 
 	    try{
 	    	instruction = (TextView)findViewById(R.id.instrcutionTextView);
 	    }catch(Exception e){}
 	    
 	    if (instruction != null) instruction.setText(str);	
	}

	public void setDisplay(String str){
		TextView display = null;
		try{
			display = (TextView)findViewById(R.id.displayTextView);
		}catch(Exception e){}
 	    
 	    if (display != null) display.setText(str);		
	}	
	
	public void refreshVolumeDisplay(){
		int vol = -1;
		try{
			AudioManager audioManager =  (AudioManager)getSystemService(Context.AUDIO_SERVICE);
			vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
		}catch(Exception e){}	
		
		TextView volume = null;
		try{
			volume = (TextView)findViewById(R.id.volumeTextView);
		}catch(Exception e){}
 	    
 	    if (volume != null){
 	 	    if (mIsMuted){ volume.setText("Muted");}
 	 	    else{ volume.setText("Volume "+ Integer.toString(vol));}
 	 	}
	}	
	
	
    protected void getWakeLock(){
	    try{
			PowerManager powerManger = (PowerManager) getSystemService(Context.POWER_SERVICE);
	        mWakeLock = powerManger.newWakeLock(PowerManager.ACQUIRE_CAUSES_WAKEUP|PowerManager.FULL_WAKE_LOCK, "de.tum.ergonomie.radio");
	        mWakeLock.acquire();
		}catch(Exception e){
        	Log.e(TAG,"get wakelock failed:"+ e.getMessage());
		}	
    }
    
	private String getVersionString(){
		String retString = "";
		String appVersionName = "";
		int appVersionCode = 0;
		try{
			appVersionName = getPackageManager().getPackageInfo(getPackageName(), 0 ).versionName;
			appVersionCode= getPackageManager().getPackageInfo(getPackageName(), 0 ).versionCode;
		}catch (Exception e) {
			Log.e(TAG, "getVersionString failed: "+e.getMessage());
		 }
		
		retString = "V"+appVersionName+"."+appVersionCode;
		
		return retString;
	}	
	
	private void toasting(final String msg, final int duration){
		Context context = getApplicationContext();
		CharSequence text = msg;
		Toast toast = Toast.makeText(context, text, duration);
        toast.setDuration(duration);
		toast.show();		
	}
	
	public File  prepareLogging(){
		File file = null;
		File folder = null;
		SimpleDateFormat  dateFormat = new SimpleDateFormat(FOLDER_DATE_STR);
		String folderTimeStr =  dateFormat.format(new Date());
		String timestamp = Long.toString(Calendar.getInstance(TimeZone.getTimeZone("UTC")).getTimeInMillis());
	   try{
		   //try to prepare external logging
		   String folderStr = Environment.getExternalStorageDirectory () + File.separator + FOLDER + File.separator + folderTimeStr;
		   file = new File(folderStr, timestamp + FILE_EXT);
		   folder = new File(folderStr);
		   folder.mkdirs();//create missing dirs
		   file.createNewFile();
		   if (!file.canWrite()) throw new Exception();
		   
			String header = HEADER +  getVersionString() + "\r\n";
		    byte[] headerBytes = header.getBytes("US-ASCII");
			writeToFile(headerBytes,file);
	
	   }catch(Exception e){
		   try{
	    	   error("maybe no SD card inserted");//toast
			   finish();//we quit. we will not continue without file logging

			   //we do not log to internal memory, its not so easy to get the files back, external is easier via usb mass storage
			   /*
			   //try to prepare internal logging
				File intfolder = getApplicationContext().getDir("data", Context.MODE_WORLD_WRITEABLE);
				String folderStr = intfolder.getAbsolutePath() + File.separator + folderTimeStr;
				toasting("logging internal to: " +folderStr, Toast.LENGTH_LONG);
				file = new File(folderStr, timestamp + FILE_EXT);
			    folder = new File(folderStr);
			    folder.mkdirs();//create missing dirs
				file.createNewFile();
				if (!file.canWrite()) throw new Exception();
				*/
		   }catch(Exception e2){
			   file= null;
	    	   error("exception during prepareLogging(): " + e2.getMessage());//toast
			   finish();//we quit. we will not continue without file logging
		   } 
		   
		  		   
		   
	   }
	   return file;
	}	
	
	
	public void logging(String action){
		 long now = Calendar.getInstance(TimeZone.getTimeZone("UTC")).getTimeInMillis(); 
		
		 //"timestamp;action;volume;mode;currentBand;desiredBand;currentFreq;desiredFreq;freqStep;"
		 
		AudioManager audioManager =  (AudioManager)getSystemService(Context.AUDIO_SERVICE);
		int vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);		
		if (mIsMuted){vol = -1;}
		 
		 
		 StringBuilder log = new StringBuilder(2048);
		 log.append(now);
		 log.append(CSV_DELIMITER);
		 log.append(action);
		 log.append(CSV_DELIMITER);
		 log.append(vol);
		 log.append(CSV_DELIMITER);
		 log.append(mStateMachine.getLogString());
		 log.append(CSV_LINE_END);
		 		 
		 
		   try{
			   String tempStr = log.toString();
			    byte[] bytes = tempStr.getBytes("US-ASCII");
				writeToFile(bytes,mFile);
		   }catch(Exception e){
			   error("error writing log data: "+e.getMessage());//toast
			   finish();//we quit. we will not continue without file logging
		   }		
	}	

	public void writeToFile(byte[] data, File file){
   		       		
   		if (data == null){//error
       		error("writeFile() data==null?!");
       		finish();//we quit. we will not continue without file logging
   		}
   		
		FileOutputStream dest = null; 
							
		try {
			dest = new FileOutputStream(file, true);
			dest.write(data);
		}catch(Exception e){
			error("writeFile() failed. msg: " + e.getMessage());
       		finish();//we quit. we will not continue without file logging
			
		}finally {
			try{
				dest.flush();
				dest.close();
			}catch(Exception e){}
		}
		
		return;
   }
	
	private void error(final String msg){//toast and log some errors
		toasting(msg, Toast.LENGTH_LONG);
		Log.e(TAG,msg);
	}		
    
}
