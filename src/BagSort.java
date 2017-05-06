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

            s.activate();
            feedBelt.forward();
            dirA = true;

            done = timeDone(false);
            prevBagClk = System.currentTimeMillis() - done;

            while (true) {
                // Await arrival of a bag
                while(s.readValue() > BLOCKED) { e.poll(mask,0); }

                Thread.sleep(800);           // SensorWait

                destA = (s.readValue() > BLACK);   // SensorRead

                Thread.sleep(2000);          // SensorRead

                if(bagWaiting){ // WaitForReverse
                    feedBelt.stop();
                    synchronized (lockObject) {
                        while(bagWaiting) {
                            lockObject.wait();
                        }
                    }
                    feedBelt.forward();
                }

                if(dirA == destA) { //beginNoChange
                    if(isLong(mask)) {
                        if(System.currentTimeMillis() - prevBagClk > AFTERBUMP || done == SHORTTIME) {
                            synchronized (lockObject) {
                                prevBagClk = System.currentTimeMillis();
                                done = timeDone(isLong(mask));
                            }
                        }
                        if(done == LONGTIME) { // WaitAfterBump
                            feedBelt.stop();
                            long delay = AFTERBUMP - (System.currentTimeMillis() - prevBagClk);
                            Thread.sleep(delay); // wait until afterbump
                            synchronized (lockObject) {
                                while(System.currentTimeMillis() - prevBagClk <= AFTERBUMP && !prevBagReset) {
                                    lockObject.wait();
                                    prevBagReset = false;
                                }
                                prevBagClk = System.currentTimeMillis();
                                feedBelt.forward();
                                done = timeDone(isLong(mask));
                            }
                        }
                        if(!isLong(mask)) { // ConsiderBumping
                            if(System.currentTimeMillis() - prevBagClk > AFTERBUMP) { // endNoChange
                                synchronized (lockObject){
                                    prevBagClk = System.currentTimeMillis();
                                    done = timeDone(isLong(mask));
                                }
                            }
                            if(System.currentTimeMillis() - prevBagClk < BEFOREBUMP) { /* endNoChange */ }
                            if((System.currentTimeMillis() - prevBagClk >= BEFOREBUMP) && (System.currentTimeMillis() - prevBagClk <= AFTERBUMP)) { // WaitNoBump
                                feedBelt.stop();
                                long delay = AFTERBUMP - (System.currentTimeMillis() - prevBagClk);
                                Thread.sleep(delay); // wait until afterbump
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
                }
                else { //beginReverse
                    if(System.currentTimeMillis() - prevBagClk <= done) { //prevBagclk <= done
                        synchronized (lockObject) {
                            bagWaiting = true;
                        }
                        feedBelt.stop();

                        long delay = done - (System.currentTimeMillis() - prevBagClk);
                        Thread.sleep(delay);

                        distBelt.reverseDirection();
                        feedBelt.forward();
                    }
                    else {
                        distBelt.reverseDirection();
                    }

                    synchronized (lockObject) {
                        done = timeDone(isLong(mask));
                        prevBagClk = System.currentTimeMillis();
                        bagWaiting = false;
                        lockObject.notify();
                    }
                }

                synchronized (lockObject) {
                    prevBagReset = false;
                }
            }
        } catch (Exception e) { }
    }

    public boolean isLong(int mask) {
        if(mask == Poll.SENSOR1_MASK) {
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

    public long timeDone(boolean isLong) {
        if(isLong) {
            return LONGTIME;
        }
        return SHORTTIME;
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



