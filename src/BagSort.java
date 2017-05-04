import java.lang.System;
import josx.platform.rcx.*;

class FeedBelt extends Thread {

    static final int BLOCKED = 70;
    static final int YELLOW  = 60;
    static final int BLACK   = 45;

    static boolean dirA; // Current direction is towards A
    static boolean bagWaiting = false; // true if bag is waiting to reverse the distribution belt
    static boolean FB_Dir[] = {true, true}; // true if feed belt is moving forward
    static boolean FBOccupied[] = {false, false}; // true if a bag is on a feed belt
    static int done = 60; // used for comparison against prevBagClk

    public Sensor s;
    public Motor feedBelt;
    public Motor distBelt;
    public short mask;

    public FeedBelt(Sensor s, Motor feedBelt, Motor distBelt, short mask){
        this.s = s;
        this.feedBelt = feedBelt;
        this.distBelt = distBelt;
        this.mask = mask;
    }

    public void run() {
        try {
            boolean destA;   // Required destination is A

            Poll e = new Poll();

            s.activate();
            feedBelt.forward();
            dirA = true;

            while (true) {
                // Await arrival of a bag
                while(s.readValue() > BLOCKED) { e.poll(mask,0); }

                Thread.sleep(800);           // Wait for colour to be valid

                destA = (s.readValue() > BLACK);   // Determine destination

                Thread.sleep(2000);          // Advance beyond sensor
                if (dirA != destA) {         // Decide whether to stop or not
                    feedBelt.stop();
                    int now = (int) System.currentTimeMillis();
                    if (now < done) Thread.sleep(done-now);
                    distBelt.reverseDirection();
                    dirA = destA;
                    feedBelt.forward();
                }

                done = ((int) System.currentTimeMillis()) + 6000;
                if (dirA) done = done + 5500;       // Extra time for long path
            }
        } catch (Exception e) { }
    }
}

public class BagSort {

    static final int BELT_SPEED = 3;

    public static void main (String[] arg) {
        // Initialize Motors
        Motor.A.setPower(BELT_SPEED);
        Motor.B.setPower(BELT_SPEED);
        Motor.C.setPower(BELT_SPEED);
        // Set Distribution belt to forward
        Motor.C.forward();

        // Declare and start both threads
        Thread f0 = new FeedBelt(Sensor.S1, Motor.A, Motor.C, Poll.SENSOR1_MASK);
        Thread f1 = new FeedBelt(Sensor.S2, Motor.B, Motor.C, Poll.SENSOR2_MASK);
        f0.start();
        f1.start();

        try{ Button.RUN.waitForPressAndRelease();} catch (Exception e) {}
        System.exit(0);
    }
}



