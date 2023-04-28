/*
File:           supercontroller.java
Project:        EnergyPlus Supercontroller+socket
Author(s):      Brian Woo-Shem
Version:        0.20 Stable
Last Updated:   2022-10-28 by Brian
Notes: Code for the optimization simulations. Should compile and run but may not have perfect results.
Run:   Set config. Run as federation

*Changelog:
	* Performance validated. Used in income level study.
    * Handles Fixed, Adaptive, and Occupancy HVAC control modes
    * Added config files & get data
    * Merged with Hannah's Appliance Scheduler v2.2 -> upgraded to multisim arrays
    * Requires OccupancyAnnualHourly
    * Complete code refactoring to combine Controller.java & Socket.java to reduce bugs
    * Permanently fix multiple simulation scalability issues
*/

package org.webgme.guest.supercontroller;

import org.cpswt.config.FederateConfig;
import org.cpswt.config.FederateConfigParser;
import org.cpswt.hla.base.AdvanceTimeRequest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

// Import extra packages
import org.cpswt.utils.CpswtUtils;
import java.io.*;
import java.net.*;
import java.lang.String;
import java.util.Random;
import java.lang.*;
import java.util.*;


public class supercontroller extends supercontrollerBase {
    private final static Logger log = LogManager.getLogger();

    private double currentTime = 0;
    
	int numSims = 0; //Define number of simulation variable here.

    public supercontroller(FederateConfig params) throws Exception {
        super(params);
    }

    private void execute() throws Exception {
        if(super.isLateJoiner()) {
            log.info("turning off time regulation (late joiner)");
            currentTime = super.getLBTS() - super.getLookAhead();
            super.disableTimeRegulation();
        }

        /////////////////////////////////////////////
        // TODO perform basic initialization below //
        /////////////////////////////////////////////
        
        // Get Number of Simulations ============================================================
        // MUST be done BEFORE declaring all arrays of length numSims otherwise the value is undefined
        System.out.println("Getting Number of Simulations: ");
        File f = new File("setNumSims.txt");
		BufferedReader br1 = new BufferedReader(new FileReader(f));
		String sn = "";
		while ((sn = br1.readLine())!=null){
			try{
				numSims = Integer.valueOf(sn);
			}
			catch (NumberFormatException en){
				System.out.println("Error: Invalid number of simulations in setNumSims.txt");
			}
			System.out.println("Detected " + numSims + " EP sims");
		}
		br1.close();
		// End Get Number of Simulations =======================================================
		
		// Declare Variables =====================================================================
		
		//Data that gets sent to EP each timestep for each sim. --------------------------
		//values sent to EnergyPlus --- Add one for each variable sent to controller
		String setHeatStr[]=new String[numSims];
		String setCoolStr[]=new String[numSims];
		//String ePeople[]=new String[numSims];
		//String eDWS[] = new String[numSims];
		String setDishwasherStr[] = new String[numSims];
		// number versions of ^
		double[] setHeat = new double[numSims];
		double[] setCool = new double[numSims];
		int[] setDishwasher = new int[numSims];
				
		//Data received from EP
		double[] indoorTemp = new double[numSims];
		double[] outdoorTemp = new double[numSims];
		/* //Currently not used:
		//humidity, heatEnergy, coolEnergy, netEnergy, energyPurchase, energySurplus, dayInt, solarRad, setHeatStrFromEP, setCoolStrFromEP
		double[] humidity = new double[numSims];
		double[] heatEnergy = new double[numSims];
		double[] coolEnergy = new double[numSims];
		double[] netEnergy = new double[numSims];
		double[] energyPurchase = new double[numSims];
		double[] energySurplus = new double[numSims];
		double[] solarRad = new double[numSims];
		int[] dayInt = new int[numSims];
		double[] setHeatStrFromEP = new double[numSims];
		double[] setCoolStrFromEP = new double[numSims];
		*/
		// --------------------------------------------------------------------------------
		
		//Obtained from config settings ------------------------------------------------
		//some have default values in case none are specified
		boolean[] usePython = new boolean[numSims];
		String bldgNames[] = new String[numSims];
		String ipAdd = "";
        int portNo = 6789;
        int nt = 12; //timesteps per hour
        int nDays = 7;
        String[] mode = new String[numSims];
        String[] heatOrCool = new String[numSims];
        char[] hcc = new char[numSims];  //  initialize with 'z' in for loop
        String dateRange = "";
        String loc = "";
        char wholesaleType = 'z';
		String priceType = "";
        String thermostatCommand[] = new String[numSims];
        boolean[] optimizeSet = new boolean[numSims]; // intialize with false; //True if optimized, false if not optimized
        boolean[] adaptiveSet = new boolean[numSims]; // intialize with false; //True if using adaptive setpoint, false if fixed setpoint. Not used if optimizeSet = true.
        boolean[] occupancySet = new boolean[numSims]; // intialize with false; //Does it use occupancy?
        boolean[] fixedSet = new boolean[numSims];
        double[] fixedMax = new double[numSims];
        double[] fixedMin = new double[numSims];
        boolean[] hasDishwasher = new boolean[numSims];
        //for Python scripts and stuff
        int numPython = 0;
        boolean writeFile = false;
        String optimizerFile = "energyOptTset2hr.py";
        String setpointFile = "occupancyAdaptSetpoints.py";
        String thermostatFile = "thermostat.py";
        // --------------------------------------------------------------------------------
        
        //Various constants
        double fuzzyOffset = 1.0;
        double fuzzyMargin = 0.1; //Distance from max allowed before it activates
        double fuzzyFudge = 0.1; //fudge factor to avoid low amounts of energy keeping it very close to the setting
        boolean[] fuzzyCool = new boolean[numSims];
        boolean[] fuzzyHeat = new boolean[numSims];
        //occExp corresponds to index = round(prob * 100 - 1)
        //occExp is array where:
		// element 0 = comfort range expansion at probability of occupancy = 0.01
		// element 1 = comfort range expansion at probability of occupancy = 0.01
		// element 98 = comfort range expansion at probability of occupancy = 0.99
		//This is used beccause Java lacks a nice norm.ppf type function as in Python
		//These constant values were taken from a Python script running a loop of all probabilities by 1% 
        double [] occExp = {10.141,9.159,8.544,8.086,7.716,7.405,7.133,6.892,6.675,6.476,6.292,6.121,5.961,5.81,5.667,5.532,5.402,5.279,5.16,5.045,4.935,4.829,4.726,4.626,4.529,4.435,4.343,4.253,4.166,4.08,3.997,3.915,3.835,3.757,3.679,3.604,3.529,3.456,3.384,3.313,3.244,3.175,3.107,3.04,2.974,2.909,2.844,2.781,2.718,2.655,2.594,2.533,2.472,2.413,2.353,2.295,2.236,2.179,2.121,2.065,2.008,1.952,1.897,1.841,1.786,1.732,1.678,1.624,1.57,1.517,1.464,1.411,1.359,1.307,1.254,1.203,1.151,1.1,1.048,0.997,0.947,0.896,0.845,0.795,0.745,0.694,0.644,0.594,0.545,0.495,0.445,0.395,0.346,0.296,0.247,0.197,0.148,0.099,0.049};
        
        //Temporary variables for Python
        char var2save = 'Z'; // default value to save nothing
        String pycmd="";
        String s = "";
        double comfTemp = 0;
        double comfExpansion = 0;
        
        // Done Declaring Variables =========================================
        
        
        // Read global settings from config.txt ====================================================
        log.info("Getting Global Configuration Settings: ");
        File file= new File("config.txt"); // In deployment folder
        BufferedReader br = new BufferedReader(new FileReader(file));
        String st = "";
        
        while ((st = br.readLine())!=null ){ //&& (ipAdd.equals("") || portNo == 0)){
            //log.info(st);
            if(st.contains("ip_address:")){
                ipAdd = br.readLine();
            }
            if(st.contains("port_number:")){
                portNo = Integer.valueOf(br.readLine());
            }
            // global
            else if(st.contains("date_range:")){
                dateRange = br.readLine();
            } 
            // global
            else if(st.contains("location:")){
                loc = br.readLine();
            }
            else if(st.contains("electricity_pricing_type:")){
                wholesaleType = br.readLine().charAt(0);
				priceType = br.readLine();
            }
            
            //List of building names. One per simulation must be listed.
            else if(st.contains("building_names")){
				for(int i = 0; i<numSims; i++){
					bldgNames[i] = br.readLine();
				}
			}
			else if(st.contains("timesteps_per_hour:")){
                nt = Integer.valueOf(br.readLine());
            }
            else if(st.contains("number_of_days:")){
                nDays = Integer.valueOf(br.readLine());
            }
            /* //Not currently in use
            else if(st.contains("write_extra_data_files:")){
                writeFile = Boolean.parseBoolean(br.readLine());
            }
            else if(st.contains("optimizer_code_file_name:")){
				optimizerFile = br.readLine();
			}
			else if(st.contains("occupancy_adaptive_setpoints_code_file_name:")){
				setpointFile = br.readLine();
			}
			*/
        }
        br.close();
        //Done Getting Global Config Settings ==================================
        
        System.out.println("IP Address: " + ipAdd);
        System.out.println("Starting Port Number: " + portNo);
        System.out.println("Date Range: " + dateRange);
        System.out.println("Location: " + loc);
        System.out.println("Wholesale Type: " + wholesaleType);
        
        
        // Declare Variables that Depend on Global Config Settings  =============================
        //For appliance scheduling
		//int nt = 12; //timesteps PER hour - constant, same as for Setpoints & Optimization
		//int state; //replaced with setDishwasher
		int runTime = 12; //number of time steps the appliance is activated
		int sleepTime = 22*nt; //time that the house is asleep
		int wakeTime = 6*nt; //time that the house is awake
		int numActPerDay = 1; //number of activations 
		int numActToday[] = new int[numSims];
		int dayCount = 1;
		double dailyActivationProb = .59;
		double activationProb = 0;
		int hour = 0;
		
		// For Occupancy & Appliance Scheduling
		int numOccupiedToday = 0;
		ArrayList<Integer> occStatus = new ArrayList<Integer>();
		ArrayList<Double> occProb = new ArrayList<Double>();
		ArrayList<Double> occComfRange = new ArrayList<Double>();
		ArrayList<Integer>[] activationHistory = new ArrayList[numSims]; // can change to just a variable, not an array list
		ArrayList<Integer>[] stateHistory = new ArrayList[numSims];
		//ArrayList<Double> randomNumHistory = new ArrayList<Double>();
		ArrayList<Integer> timeStepsOccupied = new ArrayList<Integer>();
		int timeLoop = -1; //handle if occupancy data is not long enough. 
		//gets incremented at start so set to -1 to get 0 initially.
	    //============================================================================
        
        
        // Get Individual Building Config Settings ========================================
        // Read bldg settings from config_bldgName.txt 
        // Use separate file per building to allow for quick reordering of buildings without redoing from scratch
        //  and reduced errors from keeping comma separated items in order
        for(int i = 0; i<numSims; i++){
			//remove any spaces in building name
			bldgNames[i] = bldgNames[i].replaceAll("\\s", "");
			log.info("Getting " + bldgNames[i] + " Configuration Settings: ");
			//File file= new File("config_" + bldgNames[i] + ".txt"); // In deployment folder
			file= new File("config_" + bldgNames[i] + ".txt");
			//BufferedReader br = new BufferedReader(new FileReader(file));
			br = new BufferedReader(new FileReader(file));
			//String st = "";
			st = "";
			
			while ((st = br.readLine())!=null ){
				if(st.contains("MODE") || st.contains("thermostat_type")){ 
					mode[i] = br.readLine();
				}
				else if(st.contains("heatorcool:")){
					heatOrCool[i] = br.readLine(); // Immutable
					hcc[i] = heatOrCool[i].charAt(0); // should be one of: h, c, a. MAY change during auto setting
				}
				else if(st.contains("optimize:")){
					optimizeSet[i] = Boolean.parseBoolean(br.readLine());
				}
				else if(st.contains("dishwasher:")){
					hasDishwasher[i] = Boolean.parseBoolean(br.readLine());
				}
				else if(st.contains("thermostat_code_language:")){
					thermostatCommand[i] = br.readLine();
					if (thermostatCommand[i].contains("python")){
						usePython[i] = true;
						numPython++;
					}
				}
			}
			br.close();
			
			// Need to intialize occupancySet and adaptiveSet = false and change if mode says otherwise:
            occupancySet[i] = false;
            adaptiveSet[i] = false;
            fixedSet[i] = false;
            fixedMax[i] = 23;
            fixedMin[i] = 20;
            if(mode[i].contains("occupancy")){ 
				occupancySet[i] = true; 
				//numPython++;
			}
			else if(mode[i].contains("adaptive")){ 
				adaptiveSet[i] = true;
				//numPython++;
			}
			else if(mode[i].contains("fixed")){
				fixedSet[i] = true;
				String modeNoSpace = mode[i].replaceAll("\\s", "");
				mode[i] = "fixed";
				try{
					String[] fixedAndSetpts = modeNoSpace.split(",");
					//fixedAndSetpts has form: ["fixed", "20", "23"], numbers may vary
					fixedMin[i] = Double.valueOf(fixedAndSetpts[1]);
					fixedMax[i] = Double.valueOf(fixedAndSetpts[2]);
					if(fixedMax[i]<fixedMin[i]){//swap if user reversed
						double tempMax = fixedMin[i];
						fixedMin[i] = fixedMax[i];
						fixedMax[i] = tempMax;
					}
					System.out.println("Fixed min = " + fixedMin[i] + "   max = " + fixedMax[i]);
				}
				catch(Exception ee){
					System.out.println("Warning: Could not get fixed setpoint specification, default to 20 and 23°C");
				}
			}
			else if(mode[i].equals("")){ 
				System.out.println("Text Alert: config.txt missing or contains invalid parameters."); 
			}
			System.out.println("Mode: " + mode[i]);
			System.out.println("Occupancy: " + occupancySet[i]);
			System.out.println("Adaptive: " + adaptiveSet[i]);
			System.out.println("HeatOrCool: " + hcc[i]);
			System.out.println("Dishwasher: " + hasDishwasher[i]);
			
			//Stuff that needs to be initialized at the start for each simulation (could go in a separate loop but more efficient here)
			//occStatus[i] = new ArrayList<Integer>();
			activationHistory[i] = new ArrayList<Integer>();
			stateHistory[i] = new ArrayList<Integer>();
			//timeStepsOccupied[i] = new ArrayList<Integer>();
			
			fuzzyCool[i] = false;
			fuzzyHeat[i] = false;
			
		} //done getting individual building settings ================================
        
        //Get occupancy data, Java method
        //TODO: put this inside a loop so each building can use different occupancy.
        //expect 5 minute timesteps for occupancy
        // Reading Occupancy Information ==================================================
        System.out.println("Getting Occupancy Data:");
        File occDataFile = new File("OccupancyAnnualHourly.csv");
	    BufferedReader occBr = new BufferedReader(new FileReader(occDataFile));
	    st = "";
	    occBr.readLine(); //skip header line
	    
	    int timestepsNeeded = (nDays+1) * nt * 24 + 1;
	    int dataCount = 0;
		
		while ((st = occBr.readLine())!=null && dataCount < timestepsNeeded){
		    String[] currRow = st.split(",");
		    
		    if(currRow.length > 4){
				dataCount++;
				occProb.add(Double.parseDouble(currRow[1])); //Col B
				occStatus.add(Integer.parseInt(currRow[3])); //Col D
				occComfRange.add(Double.parseDouble(currRow[4])); //Col E
			}
			else{
				System.out.println(currRow);
				System.out.println(currRow);
			}
				
	    }
	    occBr.close();
	    
	    System.out.println("Initial Occupancy Status: " + occStatus.get(0));
	    System.out.println("Initial Occupancy Prob: " + occProb.get(0));
	    System.out.println("Number of Data Points: occStatus = " + occStatus.size() + "   occProb = " + occProb.size() + "   occComfRange = " + occComfRange.size());
	    
	    //getting amount of occupancy for each day
	    for (int k = 0; k<occStatus.size(); k++) {
		    if (occStatus.get(k) == 1) {
			    numOccupiedToday = numOccupiedToday + 1;
		    }
		    if ((k+1)%(24*nt) == 0 && k!=0) {
			    timeStepsOccupied.add(numOccupiedToday); 
			    numOccupiedToday = 0;
		    }
	    }
	    System.out.println("TIME STEPS OCCUPIED PER DAY:");
	    System.out.println(timeStepsOccupied);
	    System.out.println("Occupancy Data Obtained!");
        //end of occupancy information =================================================
        
        
        // Socket Initialization ==============================================================
        log.info("Preparing for EnergyPlus simulations to join...");
        
        InetAddress addr = InetAddress.getByName(ipAdd);  // the address needs to be changed in config.txt. constant, no need for array
        ServerSocket welcomeSocket[] = new ServerSocket[numSims];
        //java.net.supercontroller connectionSocket[] = new java.net.supercontroller[numSims];
        //Socket is a standard class in Java
        java.net.Socket connectionSocket[] = new java.net.Socket[numSims];
        InputStreamReader inFromClient[] = new InputStreamReader[numSims];
        BufferedReader buffDummy[] = new BufferedReader[numSims];
        DataOutputStream outToClient[] = new DataOutputStream[numSims];

        // Add socket --- Brian converted to loop for multiple sockets
        for (int i = 0; i<numSims; i++){
			int porti = portNo+i;
			log.info("Waiting for EnergyPlus Simulation at " + porti);
			welcomeSocket[i] = new ServerSocket(portNo+i, 50, addr);  // Can also be changed in config.txt
			connectionSocket[i] = welcomeSocket[i].accept(); // initial connection will be made at this point
			
			log.info("Connection to EnergyPlus simulation at " + porti + " successful!");
		 
			inFromClient[i] = new InputStreamReader(connectionSocket[i].getInputStream());
			log.info("Input Stream from " + porti + " configured");
			buffDummy[i] = new BufferedReader(inFromClient[i]);
			log.info("Buffered reader from " + porti + " configured");
			outToClient[i] = new DataOutputStream(connectionSocket[i].getOutputStream());
			log.info("Output Stream to " + porti + " configured");
		}
        // done adding socket ==========================================================
        
        log.info("All EnergyPlus Simulations added successfully!");

        AdvanceTimeRequest atr = new AdvanceTimeRequest(currentTime);
        putAdvanceTimeRequest(atr);

        if(!super.isLateJoiner()) {
            log.info("waiting on readyToPopulate...");
            readyToPopulate();
            log.info("...synchronized on readyToPopulate");
        }

        ///////////////////////////////////////////////////////////////////////
        // TODO perform initialization that depends on other federates below //
        ///////////////////////////////////////////////////////////////////////

        if(!super.isLateJoiner()) {
            log.info("waiting on readyToRun...");
            readyToRun();
            log.info("...synchronized on readyToRun");
        }

        startAdvanceTimeThread();
        log.info("started logical time progression");
        
        // Define variables for getting EP data
        String header, time="0", varName="", value="";

        while (!exitCondition) {
            atr.requestSyncStart();
            enteredTimeGrantedState();

            ////////////////////////////////////////////////////////////////////
            // TODO break here if ready to resign and break out of while loop //
            ////////////////////////////////////////////////////////////////////
            
            //Once per new timestep:
            System.out.println("Timestep = " + currentTime);
            try{
				timeLoop++;
				System.out.println("Occupancy at " + timeLoop + " = "+ occStatus.get(timeLoop));
			}
			catch(IndexOutOfBoundsException iob){ //handle if occupancy data is too short without crashing
				timeLoop = 0;
				System.out.println("End of occupancy data detected. Reset timeLoop = " + timeLoop);
			} 
			// Compute current hour for occupancy data etc. based on timeloop. casting (int) always truncates.
			hour = (int) timeLoop / nt;
            
            // Begin for loop over i sims ================================================================
            for (int i = 0; i<numSims; i++){
				//BEGIN GETTING DATA FROM EP ========================================================
				//reset before receiving data from next EP Sim
				varName=""; 
				value="";
				
				if((header = buffDummy[i].readLine()).equals("TERMINATE")){
					exitCondition = true;
				}
				time = buffDummy[i].readLine();
				System.out.println("EP SimTime = " + time);
				System.out.println("===> Received from EP #" + i + " <===");
				
				while(!(varName = buffDummy[i].readLine()).isEmpty()) {
					value = buffDummy[i].readLine();
					System.out.println("    " + varName + " = " + value);
					// Add any variable that you want to get from EnergyPlus here...
					// Names have to match the modelDescription.xml file
					// before @ is varName and before , is value
					// varName first!!!
					if(varName.equals("epSendOutdoorAirTemp")){
						outdoorTemp[i] = Double.parseDouble(value);
					}
					else if(varName.equals("epSendZoneMeanAirTemp")){
						indoorTemp[i] = Double.valueOf(value);
					}
					/*
					 * //These will be implemented later - get rid of dataString, convert to double or string instead.
					 * //humidity, heatEnergy, coolEnergy, netEnergy, energyPurchase, energySurplus, dayInt, solarRad, setHeatStrFromEP, setCoolStrFromEP
					else if(varName.equals("epSendZoneHumidity")){
						humidity[i] = Double.valueOf(value);
					}
					else if(varName.equals("epSendHeatingEnergy")){
						heatEnergy[i] = Double.valueOf(value);
					}
					else if(varName.equals("epSendCoolingEnergy")){
						coolEnergy[i] = Double.valueOf(value);
					}
					else if(varName.equals("epSendNetEnergy")){
						netEnergy[i] = Double.valueOf(value); 
					}
					else if(varName.equals("epSendEnergyPurchased")){
						energyPurchase[i] = Double.valueOf(value);
					}
					else if(varName.equals("epSendEnergySurplus")){
						energySurplus[i] = Double.valueOf(value); 
					}
					else if(varName.equals("epSendDayOfWeek")){
						dayInt[i] = Double.valueOf(value);
					}
					else if(varName.equals("epSendSolarRadiation")){
						solarRad[i] = Double.valueOf(value);
					}
					else if(varName.equals("epSendHeatingSetpoint")){
						setHeatStrFromEP[i] = Double.valueOf(value);
					}
					else if(varName.equals("epSendCoolingSetpoint")){
						setCoolStrFromEP[i] = Double.valueOf(value); 
					}
					*/
				}
				
				//END GET DATA FROM EP ============================================================
				
				//BEGIN COMPUTING HVAC SETPOINTS ============================================================
				
				//Reset with flag values to detect errors.
				setHeat[i] = -1.1;
				setCool[i] = 111.1;
				setHeatStr[i] = "0.0";
				setCoolStr[i] = "99.9";
				
				// BEGIN PYTHON SETPOINT OPTION ~~~~~~~~~~~~~~~~~~~~~~~~~~~
				if (usePython[i]==true){
					var2save = 'Z'; // default value to save nothing
					
					pycmd="";
					s="";
					try {
						Process pro;
						
						//some of these aren't implemented or aren't needed
						//pycmd = pythonCommand + " ./" + thermostatFile + " -s indoorTemp" +String.valueOf(indoorTemp[i])+" ourdoorTemp" + String.valueOf(outdoorTemp[i])+ " occupancyStatus=" + String.valueOf(occStatus.get(currentTime)) + " occupancyProb=" + String.valueOf(occupancyProb) + " heatOrCool=" + hcc[i] + " MODE=" + mode[i] + " date_range=" + dateRange + " loc=" + loc + " price=" + priceType;
						
						pycmd = thermostatCommand[i] + " " + thermostatFile + " -s indoorTemp=" +String.valueOf(indoorTemp[i])+" ourdoorTemp=" + String.valueOf(outdoorTemp[i])+ " occupancyStatus=" + String.valueOf(occStatus.get((int)currentTime)) + " heatOrCool=" + hcc[i] + " MODE=" + mode[i];
						
						/*
						// Call Python optimization & occupancy code with necessary info
						if (optimizeSet[i]){
							pycmd = pythonCommand + " ./" + optimizerFile + " " + sday +" " +sblock +" "+ String.valueOf(zoneTemps[i])+ " " + String.valueOf(24) + " " + nt + " " + hcc[i] + " " + mode[i] + " " + dateRange + " " + loc + " " + priceType; 
						}
						else{ // Call Python adaptive and occupancy setpoints code with necessary info
							pycmd = pythonCommand + " ./" + setpointFile + " " +sday +" " +sblock +" "+ String.valueOf(zoneTemps[i])+ " " + String.valueOf(24) + " " + nt + " " + hcc[i] + " " + mode[i] + " " + dateRange + " " + loc + " " + priceType;
						}
						*/
						System.out.println("Run:  " + pycmd); //Display command used for debugging
						pro = Runtime.getRuntime().exec(pycmd); // Runs command

						BufferedReader stdInput = new BufferedReader(new InputStreamReader(pro.getInputStream()));
						
						// Gets input data from Python that will either be a keystring or a variable. 
						// AS long as there is another output line with data,
						while ((s = stdInput.readLine()) != null) {
							//System.out.println(s);  //for debug
							// New nested switch-case to reduce computing time and fix so it's not appending data meant for the next one. - Brian
							// Replaced a bunch of booleans with single key char var2save - Brian
							// If current line is a keystring, identify it by setting the key var2save to that identity
							switch (s) {
								/*
								case "energy consumption":
									var2save = 'E';
									break;
								case "indoor temp prediction":
									var2save = 'T';
									break;
								case "pricing per timestep":
									var2save = 'P';
									break;
								case "outdoor temp":
									var2save = 'O';
									break;
								case "solar radiation": 
									var2save = 'S'; 
									break;
								*/
								case "thermostat_set_heat": 
									var2save = 'H'; 
									break;
								case "thermostat_set_cool": 
									var2save = 'C'; 
									break;
								case "Traceback (most recent call last):":
									System.out.println("\nHiss... Python crash detected. Try pasting command after \"Run\" in the terminal and debug Python.");
									var2save = 'Z';
									break;
								default: // Not a keystring, so it is probably data
									switch(var2save) {
										case 'H': setHeatStr[i] = s; break;
										case 'C': setCoolStr[i] = s; break;
										/*
										case 'E': dataStringOptE = dataStringOptE + separatorOpt + s; break;
										case 'T': dataStringOptT = dataStringOptT + separatorOpt + s; break;
										case 'P': dataStringOptP = dataStringOptP + separatorOpt + s; break;
										case 'O': dataStringOptO = dataStringOptO + separatorOpt + s; break;
										case 'S': dataStringOptS = dataStringOptS + separatorOpt + s; break;
										case 'H': dsoHeatSet = dsoHeatSet + separatorOpt + s; break;
										case 'C': dsoCoolSet = dsoCoolSet + separatorOpt + s; break;
										*/
										default: // Do nothing; it's ok if unneeded strings come through.
									} // End var2save switch case
							} // End s switch case
						} //End while next line not null
					} // End try
					catch (IOException e) {
						e.printStackTrace();
						System.out.println("\nHiss... Python crashed or failed to run. Try pasting command after \"Run\" in the terminal and debug Python."); 
					}
					// Extra check if no keystrings found, var2save will still be default 'Z'. Controller will probably crash after this, but it is usually caused by Python code crashing and not returning anything. Warn user so they debug correct program.
					if (var2save == 'Z') { System.out.println("Hiss... No keystrings from Python found. Python may have crashed and returned null. Check command after \"Run:\""); }
					
					//Assumption: fuzzy control is implemented inside Python.
				} 
				// END PYTHON SETPOINT OPTION ~~~~~~~~~~~~~~~~~~~~~~~~~~~
				
				// BEGIN JAVA SETPOINTS OPTION ~~~~~~~~~~~~~~~~~~~~~~~~~~~
				else{
					// Fixed setpoint
					if(fixedSet[i]==true){
						setHeat[i] = fixedMin[i];
						setCool[i] = fixedMax[i];
					}
					// Anything based on adaptive comfort model.
					else {
						//compute mean comfortable temperature (stored as comfTemp, a temporary variable)
						if(outdoorTemp[i] <= 9.6774){
							comfTemp = 20.9;
						}
						else if(outdoorTemp[i] < 33.22){
							comfTemp = 17.9 + 0.31*outdoorTemp[i];
						}
						else{ //outdoorTemp[i] > 33.548
							comfTemp = 28.2;
						}
						// Adaptive 90
						if(adaptiveSet[i]==true){
							setHeat[i] = comfTemp - 2;
							setCool[i] = comfTemp + 2;
						}
						//Likely this will become "Manual control" setting
						// shut on and off manually with some kind of random factor for forgetfulness.
						// reset periodically if uncomfortable enough.
						else if(occupancySet[i]==true){
							
							//Should work once occProb is implemented
							if(occStatus.get(hour)==1.0){
								setHeat[i] = comfTemp - 2;
								setCool[i] = comfTemp + 2;
							}
							else{
								//not the most efficient but should work I think.
								System.out.println("OccProb = " + occProb.get(hour));
								//Using Math.round() to handle rounding up for decimal value > 0.5
								//occExp is array where:
								// element 0 = comfort range expansion at probability = 0.01
								// element 1 = comfort range expansion at probability = 0.01
								// element 98 = comfort range expansion at probability = 0.99
								//This is used beccause Java lacks a nice norm.ppf type function as in Python
								comfExpansion = occExp[(int) Math.round(occProb.get(hour) * 100 - 1)];
								System.out.println("ComfExpansion = " + comfExpansion);
								setHeat[i] = comfTemp - 2 -comfExpansion;
								setCool[i] = comfTemp + 2 +comfExpansion;
							} //end else
						} //end else if (occupancySet[i]==true)
					} //end anything adaptive-based
					
					System.out.println("Java setpoints before fuzzy & heat/cool:");
					System.out.println("setCool[" + i + "] = " + setCool[i]);
					System.out.println("setHeat[" + i + "] = " + setHeat[i]);
					
					//Fuzzy control -----------------------------------------------------------------------------
					//booleans fuzzyCool[i] and fuzzyHeat[i] are for toggle effect; otherwise it would activate barely
					// above the margin then shut off until it gets close to the margin again
					//margin is safety to account for cooling/heating delay so it stays inside setpoints
					//fudge handles when EP stays right at the threshold but doesn't hit it (unrealistic)
					//toggle on when indoor temp is hotter than cooling setpoint (with margin)
					if(indoorTemp[i] >= setCool[i] - fuzzyMargin - fuzzyFudge){
						fuzzyCool[i] = true;
						//setCool[i] -= fuzzyOffset + fuzzyMargin;
					} //toggle off if indoor temp is colder than cooling setpoint + offest
					else if(indoorTemp[i] <= setCool[i] - (fuzzyMargin + fuzzyOffset) + fuzzyFudge){
						fuzzyCool[i] = false;
						//setCool[i] -= fuzzyMargin;
					}
					if(fuzzyCool[i]){ //make setpoint colder by offset and margin
						setCool[i] -= fuzzyOffset + fuzzyMargin;
						System.out.println("Activated fuzzy for cooling"); //TODO: Remove this line
					}
					else{ //only margin
						setCool[i] -= fuzzyMargin;
					}
					
					// toggle on when it gets colder than heating setpoint with margin
					if(indoorTemp[i] <= setHeat[i] + fuzzyMargin + fuzzyFudge){
						fuzzyHeat[i] = true;
					} // toggle off if hotter than heating setpoint with offset
					else if(indoorTemp[i] >= setHeat[i] + fuzzyMargin + fuzzyOffset - fuzzyFudge){
						fuzzyHeat[i] = false;
					}
					if(fuzzyHeat[i]){ //increase setpoint by offset and margin
						setHeat[i] += fuzzyOffset + fuzzyMargin;
					}
					else{ //only margin
						setHeat[i] += fuzzyMargin;
					}
					// End Java Fuzzy Control ---------------------------------
					
					//Remove heating if in cooling mode
					if(hcc[i] == 'c'){
						setHeat[i] = 0.0;
					}
					//Remove cooling if in heating mode
					else if(hcc[i] == 'h'){
						setCool[i] = 50.0;
					}
					
					//Create string from final setpoints as doubles
					setCoolStr[i] = Double.toString(setCool[i]);
					setHeatStr[i] = Double.toString(setHeat[i]);
					
					System.out.println("Java setpoints after fuzzy:");
					System.out.println("setCool[" + i + "] = " + setCoolStr[i]);
					System.out.println("setHeat[" + i + "] = " + setHeatStr[i]);
				}
				// END JAVA SETPOINTS OPTION ~~~~~~~~~~~~~~~~~~~~~~~~~~~
				
				//END COMPUTING HVAC SETPOINTS ============================================================
				
				//BEGIN APPLIANCE SCHEDULER ============================================================
                
                if(hasDishwasher[i] == true){
					//initialize for beginning
					if (timeLoop == 0) {
						System.out.println("DAY COUNTER: " + dayCount);
						activationProb = dailyActivationProb/timeStepsOccupied.get(dayCount-1);
						System.out.println(activationProb);
					}
					//if beyond end of occupancy data
					else if (timeLoop+1 >= occStatus.size()){
						System.out.println("ERROR: NO OCCUPANCY DATA EXISTS FOR TIME = " + timeLoop);
					}
					//reset for new day
					else if ((timeLoop+1)%(24*nt) == 0) {
						numActToday[i] = 0;
						//dayCount = dayCount + 1;
						// Problem: original version looped once for single sim
						// multisim loops once per simulation, so for 2 simulations, daycount will increment twice at 
						// each new day. 
						// Replace with formula instead - Brian
						// currentTime represents 5 mins elapsed
						// nt = 12 constant
						dayCount = (int)(timeLoop+1)/(24*nt);
						// should be int anyway, but forcing int to be safe
						
						sleepTime = sleepTime + nt*24;
						wakeTime = wakeTime + nt*24;
						System.out.println("DAY COUNTER: "+ dayCount); // failed after dayCount = 9
						activationProb = dailyActivationProb/timeStepsOccupied.get(dayCount-1);
						System.out.println(activationProb);
					}	
					//make sure activation history takes precedence
					if (activationHistory[i].size() > 0 && activationHistory[i].size() < runTime){
						setDishwasher[i] = 1;
						activationHistory[i].add(setDishwasher[i]);
						stateHistory[i].add(setDishwasher[i]);
						//randomNumHistory.add(0.0);
					}else {
						//dealing with occupancy//dealing with wake/sleep time//dealing with number of activations per day
						if (occStatus.get(timeLoop) == 1 && currentTime > wakeTime && currentTime < sleepTime &&  numActToday[i] < numActPerDay) {
							//dealing with length of operation
							if (activationHistory[i].size() == runTime) {
								setDishwasher[i] = 0;
								stateHistory[i].add(setDishwasher[i]);
								numActToday[i] = numActToday[i] + 1;
								activationHistory[i].clear();
								//randomNumHistory.add(0.0);
							}else if (activationHistory[i].size() == 0) {
								double randomNum = Math.random(); //random num for monte carlo or add whatever determiner I decide
								//randomNumHistory.add(randomNum);
								System.out.println("Random number for activation: " + randomNum);
								System.out.println("Activation probability: " + activationProb);
								if (randomNum < activationProb) {
									setDishwasher[i] = 1;
									activationHistory[i].add(setDishwasher[i]);
									stateHistory[i].add(setDishwasher[i]);
									System.out.println("Dishwasher Activated");
								}else {
									setDishwasher[i] = 0;
									stateHistory[i].add(setDishwasher[i]); // end determiners
								}
							}
						}
						else {
							setDishwasher[i] = 0;
							stateHistory[i].add(setDishwasher[i]);
							//randomNumHistory.add(0.0);
						}
					}
					//System.out.println("STATE HISTORY:"); //These never get reset so by end of simulation there may be thousand or more elements!
					//System.out.println(stateHistory[i]);
					//System.out.println("RANDOM NUMBERS:");
					//System.out.println(randomNumHistory);
					//setDishwasherStr[i] = String.valueOf(setDishwasher[i]);
				}
				else{
					setDishwasher[i] = 0;
				}
				setDishwasherStr[i] = String.valueOf(setDishwasher[i]);

                //END APPLIANCE SCHEDULER =================================================================
				
				
				
				// Send strings containing setpoint instructions to EP ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
				outToClient[i].writeBytes("SET\r\n" + time + "\r\nepGetStartCooling\r\n" + setCoolStr[i] + "\r\nepGetStartHeating\r\n" + setHeatStr[i] + "\r\ndishwasherSchedule\r\n" + setDishwasherStr[i] + "\r\n\r\n");
				System.out.println("<=== SENT to EP for #" + i + " ===>\n\tTime = " + time +  "\n\tepGetStartCooling = " + setCoolStr[i] + "\n\tepGetStartHeating = " + setHeatStr[i] + "\n\tdishwasherSchedule = " + setDishwasherStr[i] + "\r\n");
				
				outToClient[i].flush();
				// Done sending data to EP ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
			} // END Loop for all sims ==========================================================================
            

            if (!exitCondition) {
                currentTime += super.getStepSize();
                AdvanceTimeRequest newATR =
                    new AdvanceTimeRequest(currentTime);
                putAdvanceTimeRequest(newATR);
                atr.requestSyncEnd();
                atr = newATR;
            }
        }

        // call exitGracefully to shut down federate
        exitGracefully();

        //////////////////////////////////////////////////////////////////////
        // TODO Perform whatever cleanups are needed before exiting the app //
        //////////////////////////////////////////////////////////////////////
    }

    public static void main(String[] args) {
        try {
            FederateConfigParser federateConfigParser =
                new FederateConfigParser();
            FederateConfig federateConfig =
                federateConfigParser.parseArgs(args, FederateConfig.class);
            supercontroller federate =
                new supercontroller(federateConfig);
            federate.execute();
            log.info("Supercontroller execution completed successfully!");
            System.exit(0);
        }
        catch (Exception e) {
            log.error(e);
            System.exit(1);
        }
    }
}
