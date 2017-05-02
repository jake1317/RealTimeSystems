
import bagsortsim.BagSortSim;


/**
 * DTU, Course 02224, Real-Time Systems
 * 
 * Example showing how to run the Baggage Sorting Simulator
 * 
 * The Travellers thread shows how to stimulate the simulation
 * automatically.
 */
public class RunSimulation {
		
	public static void main(String[] args) {

		BagSortSim sim = BagSortSim.getSimulator();
		
		/* Set simulation parameters */
		sim.setFeedCheck(true);
		sim.setSeparation(26); 		// Leave enough room for previous to leave belt before stop
                                    // This large value is due to the early stop used by SingleSort
	
		
		/* Start simulator and environment (travellers) */
		sim.start();
		new Travellers(sim).start(); 
		
		/* Run control program */
		SingleSort.main(args);
		
	}

}

class Travellers extends Thread {
	
	BagSortSim sim;
	
	public Travellers (BagSortSim sim) {
		this.sim = sim;
	}
	
	public void run() {
		try {
			while (true) {
				sleep((int) (Math.random()*5000));
				int counter = (Math.random() < 0.5 ? 2 : 2);  // Use only Feed 2 for SingleSort
				int color   = (Math.random() < 0.6 ? BagSortSim.YELLOW  : BagSortSim.BLACK);
				sim.checkin(counter,color);
			}
		} catch (InterruptedException e) {}
	}
	
}


