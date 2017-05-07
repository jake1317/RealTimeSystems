import java.lang.System;

import com.sun.jna.platform.win32.WinDef;
import josx.platform.rcx.*;
import kotlin.jvm.Synchronized;

class FeedBelt extends Thread {

    static final int BLOCKED    = 70;
    static final int YELLOW     = 60;
    static final int BLACK      = 45;
    static final int LONGTIME   = 11500;
    static final int SHORTTIME  = 6000;
    static final int AFTERBUMP  = 8000;
    static final int BEFOREBUMP = 3000;

    static Object lockObject = new Object();
    static boolean dirA; // Current direction is towards A
    static boolean bagWaiting = false; // true if bag is waiting to reverse the distribution belt
    static boolean prevBagReset = false;
    //static boolean FB_Dir[] = {true, true}; // true if feed belt is moving forward
    //static boolean FBOccupied[] = {false, false}; // true if a bag is on a feed belt
    static long done; // used for comparison against prevBagClk
    static long prevBagClk;

    public Sensor s;
    public Motor feedBelt;
    public Motor distBelt;
    public short mask;

    boolean destA; // destination is A

    public FeedBelt(Sensor s, Motor feedBelt, Motor distBelt, short mask){
        this.s = s;
        this.feedBelt = feedBelt;
        this.distBelt = distBelt;
        this.mask = mask;
    }

    public void run() {
        try {
            Poll e = new Poll();

            // initialize sensors and belts
            s.activate();
            feedBelt.forward();
            dirA = true;

            // initialize done and prevBagclk
            synchronized (lockObject) {
                done = timeDone(false);
                prevBagClk = System.currentTimeMillis() - done;
            }

            // begin infinite loop
            while (true) {
                // Await arrival of a bag
                while(s.readValue() > BLOCKED) { e.poll(mask,0); }

                Thread.sleep(800);           // SensorWait

                destA = (s.readValue() > BLACK);   // SensorRead

                Thread.sleep(2000);          // SensorRead

                if(bagWaiting){ // WaitForReverse

                    feedBelt.stop();
                    synchronized (lockObject) { // wait for bagwaiting to be false
                        while(bagWaiting) {
                            lockObject.wait();
                        }
                    }
                    feedBelt.forward();
                }

                if(dirA == destA) { //beginNoChange

                    // all non-bumping conditions
                    if(isLong(mask)) {

                        // continue to end, no waiting required
                        if (System.currentTimeMillis() - prevBagClk > AFTERBUMP || done == SHORTTIME) {

                            synchronized (lockObject) {
                                prevBagClk = System.currentTimeMillis();
                                done = timeDone(isLong(mask));
                            }
                        }

                        else if (done == LONGTIME) { // WaitAfterBump

                            feedBelt.stop();
                            long delay = AFTERBUMP - (System.currentTimeMillis() - prevBagClk);
                            if (delay > 0) {
                                Thread.sleep(delay); // wait until afterbump
                            }

                            synchronized (lockObject) { // issues with dealing with broadcast channel (can enter deadlock)
                                while (System.currentTimeMillis() - prevBagClk <= AFTERBUMP && !prevBagReset) {
                                    lockObject.wait();
                                }
                                prevBagReset = false;
                                prevBagClk = System.currentTimeMillis();
                                done = timeDone(isLong(mask));
                            }
                            feedBelt.forward();
                        }
                    }

                    if(!isLong(mask)) { // ConsiderBumping

                        // no need to wait, bag is entering after other bag
                        if(System.currentTimeMillis() - prevBagClk > AFTERBUMP) {
                            synchronized (lockObject){
                                prevBagClk = System.currentTimeMillis();
                                done = timeDone(isLong(mask));
                            }
                        }

                        // no need to wait or reset variables, bag is entering before other bag
                        else if(System.currentTimeMillis() - prevBagClk < BEFOREBUMP) { }

                        // wait to avoid bumping
                        else if((System.currentTimeMillis() - prevBagClk >= BEFOREBUMP) &&
                                (System.currentTimeMillis() - prevBagClk <= AFTERBUMP)) { // WaitNoBump

                            feedBelt.stop();
                            long delay = AFTERBUMP - (System.currentTimeMillis() - prevBagClk);
                            if(delay > 0){
                                Thread.sleep(delay); // wait until afterbump
                             }
                            feedBelt.forward();
                            synchronized (lockObject) {
                                prevBagClk = System.currentTimeMillis();
                                done = timeDone(isLong(mask));
                                prevBagReset = true;
                                lockObject.notify();
                            }
                        }
                    }
                }
                else { //beginReverse

                    // Wait to reverse the distribution belt
                    if(System.currentTimeMillis() - prevBagClk <= done) {

                        synchronized (lockObject) {
                            bagWaiting = true;
                        }

                        feedBelt.stop();
                        long delay = done - (System.currentTimeMillis() - prevBagClk);
                        if(delay > 0) {
                            Thread.sleep(delay);
                        }

                        distBelt.reverseDirection();
                        dirA = !dirA;
                        feedBelt.forward();
                    }

                    // no need to wait, no bags on the distribution belt
                    else {
                        distBelt.reverseDirection();
                        dirA = !dirA;
                    }

                    //reset variables and release a potentially waiting bag on the other feed belt
                    synchronized (lockObject) {
                        done = timeDone(isLong(mask));
                        prevBagClk = System.currentTimeMillis();
                        bagWaiting = false;
                        lockObject.notify();
                    }
                }

                // hacky way of dealing with broadcast channel
                synchronized (lockObject) {
                    prevBagReset = false;
                }
            }
        } catch (Exception e) { }
    }

    // function used to determine how long a bag will take on the distribution belt
    public boolean isLong(int mask) {
        if(mask == Poll.SENSOR2_MASK) {
            if(destA) {
                return true;
            }
            return false;
        }
        if(destA){
            return false;
        }
        return true;
    }

    // mapping booleans to constants
    public long timeDone(boolean isLong) {
        return isLong? LONGTIME: SHORTTIME;
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



