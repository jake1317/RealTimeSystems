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

    static Object timeLockObject = new Object();
    static Object bagWaitLockObject = new Object();
    static boolean dirA; // Current direction is towards A
    static boolean bagWaiting = false; // true if bag is waiting to reverse the distribution belt
    //static boolean FB_Dir[] = {true, true}; // true if feed belt is moving forward
    //static boolean FBOccupied[] = {false, false}; // true if a bag is on a feed belt
    static int done; // used for comparison against prevBagClk
    static int prevBagClk;

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
            synchronized (timeLockObject) {
                done = timeDone(false);
                prevBagClk = (int) System.currentTimeMillis() - done;
            }

            // begin infinite loop
            while (true) {
                // Await arrival of a bag
                while(s.readValue() > BLOCKED) { e.poll(mask,0); }

                Thread.sleep(800);           // SensorWait

                destA = (s.readValue() > BLACK);   // SensorRead

                System.out.println((destA?"Yellow":"Black") + " Bag Checked in on " + mask);

                Thread.sleep(2000);          // SensorRead

                if(bagWaiting){ // WaitForReverse

                    feedBelt.stop();
                    synchronized (bagWaitLockObject) { // wait for bagwaiting to be false
                        System.out.println("FB " + mask + " wait for reverse.");
                        while(bagWaiting) {
                            bagWaitLockObject.wait();
                        }
                    }
                    feedBelt.forward();
                }

                if(dirA == destA) { //beginNoChange

                    // all non-bumping conditions
                    if(isLong(mask)) {

                        // continue to end, no waiting required
                        if (System.currentTimeMillis() - prevBagClk > AFTERBUMP || done == SHORTTIME) {

                            synchronized (timeLockObject) {
                                prevBagClk = (int) System.currentTimeMillis();
                                done = timeDone(isLong(mask));
                            }
                        }

                        else if (done == LONGTIME) { // WaitAfterBump

                            feedBelt.stop();
                            synchronized (timeLockObject) { // issues with dealing with broadcast channel (can enter deadlock)
                                int timeAfterBump = (int) (AFTERBUMP + prevBagClk);
                                System.out.println("FB " + mask + " wait to prevent too many bags on dist belt");
                                while (System.currentTimeMillis() < timeAfterBump) {
                                    timeLockObject.wait(timeAfterBump - System.currentTimeMillis());
                                }
                                prevBagClk = (int) System.currentTimeMillis();
                                done = timeDone(isLong(mask));
                            }
                            feedBelt.forward();
                        }
                    }

                    if(!isLong(mask)) { // ConsiderBumping

                        // no need to wait, bag is entering after other bag
                        if(System.currentTimeMillis() - prevBagClk > AFTERBUMP) {
                            synchronized (timeLockObject){
                                prevBagClk = (int) System.currentTimeMillis();
                                done = timeDone(isLong(mask));
                            }
                        }

                        // no need to wait or reset variables, bag is entering before other bag
                        else if(System.currentTimeMillis() - prevBagClk < BEFOREBUMP) { }

                        // wait to avoid bumping
                        else if((System.currentTimeMillis() - prevBagClk >= BEFOREBUMP) &&
                                (System.currentTimeMillis() - prevBagClk <= AFTERBUMP)) { // WaitNoBump

                            feedBelt.stop();
                            synchronized (timeLockObject) {
                                System.out.println("FB " + mask + " wait until after bump");
                                while(System.currentTimeMillis() - prevBagClk < AFTERBUMP) {
                                    int delay = (int) (AFTERBUMP - (System.currentTimeMillis() - prevBagClk));
                                    if(delay <= 0) {
                                        break;
                                    }
                                    timeLockObject.wait(delay); // wait until afterbump
                                }
                             }
                            feedBelt.forward();
                            synchronized (timeLockObject) {
                                if(!(done == LONGTIME && System.currentTimeMillis() - prevBagClk < BEFOREBUMP)) {
                                    prevBagClk = (int) System.currentTimeMillis();
                                    done = timeDone(isLong(mask));
                                }
                                timeLockObject.notify();
                            }
                        }
                    }
                }
                else { //beginReverse

                    // Wait to reverse the distribution belt
                    if(System.currentTimeMillis() - prevBagClk <= done) {

                        synchronized (bagWaitLockObject) {
                            bagWaiting = true;
                        }

                        feedBelt.stop();
                        while(System.currentTimeMillis() - prevBagClk < done) {
                            int delay = (int) (done - (System.currentTimeMillis() - prevBagClk));
                            if(delay <= 0) { break; }
                            System.out.println("FB " + mask + " wait to reverse");
                            Thread.sleep(delay);
                        }


                        System.out.println("FB " + mask + " reversing");
                        distBelt.reverseDirection();
                        dirA = !dirA;
                        feedBelt.forward();
                    }

                    // no need to wait, no bags on the distribution belt
                    else {
                        System.out.println("FB " + mask + " reversing");
                        distBelt.reverseDirection();
                        dirA = !dirA;
                    }

                    //reset variables and release a potentially waiting bag on the other feed belt
                    synchronized (timeLockObject) {
                        done = timeDone(isLong(mask));
                        prevBagClk = (int) System.currentTimeMillis();
                    }
                    synchronized (bagWaitLockObject) {
                        bagWaiting = false;
                        bagWaitLockObject.notify();
                    }
                }
                System.out.println((destA?"Yellow":"Black") + " exited from " + mask);
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
    public int timeDone(boolean isLong) {
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



