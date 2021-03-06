# RadioTuning
Manual radio tuning application for Android for (driver distraction) studies/experiments.

For more information: http://www.lfe.mw.tum.de/radiotask/
The compiled app can be downloaded in the Playstore, for free: https://play.google.com/store/apps/details?id=de.tum.mw.lfe.radio

In the field of driver distraction radio tuning is often seen as a kind of reference. The task was carried out over years and the risk seems socially accepted. 
The AAM guideline (official title: “Statement of Principles, Criteria and Verification Procedures on Driver Interactions with Advanced In-Vehicle Information and Communication Systems Including 2006 Updated Sections, Driver Focus-Telematics Working Group June 26, 2006″ http://www.autoalliance.org/index.cfm?objectid=D6819130-B985-11E1-9E4C000C296BA163 ) even specifies on pp.46 a radio tuning task for driver distraction studies.
These kind of reference tasks are sometimes used in laboratory studies, e.g. to get a reference or to compare values between laboratories. Other examples of highly standardized tasks are the Surrogate Reference Task (SuRT) and Critical Tracking Task (CTT) from ISO/TS 14198:2012.

The radio tuning procedure of AAM has the problem that it often has to be adapted to the specific hardware used in an experiment (e.g., knob for radio tuning instead of buttons). Newer radios sometimes even don't provide the possibility to switch to the manual radio tuning mode, that is needed for the procedure. And overall you need some hardware effort to apply the method to your experimental setup.

This app implements an AAM-like radio tuning (with some modifications) on a mock-up radio interface for Android. At the moment the data basis and evaluations are not sufficient to say, it is a replacement for the original hardware radio. If it is further devoped and tested it maybe can fill the gap between the highly specific and artifical tasks (e.g., SuRT and CTT) and natural tasks (e.g., phone calls).
At the moment we typically apply it e.g., when demonstrating or testing eye-tarcking or quickly need a secondary task.
For comparisions we recommend to use it on a tablet with least: 6.5”, 800x480 and 160dpi or better. 30° below normal line of sight or higher. The 95dpx95dp buttons should have dimensions of 1.5cm. 
For teaching, e.g. Lane Change Test (LCT) or eye tracking, also smartphone setups work fine.

The app can be also controlled via Bluetooth or OTG keyboard.

Log-files are saved to a folder on the smartphone

------
For legal reasons we are not allowed to upload the (foreign) music sound files to github or re-license the files. For our own voice files we decided to take the same way (they are not in this github repository). If you want to program, you may find within 10 seconds how to get the sound files out of an Android apk. So, you can develop, contribute to the project and setup own experiments.

If you fork and would make an own public app, you have to get other sound files or solutions.

---------
[![DOI](https://zenodo.org/badge/12944/InstituteOfErgonomics/RadioTuning.svg)](http://dx.doi.org/10.5281/zenodo.17612)
