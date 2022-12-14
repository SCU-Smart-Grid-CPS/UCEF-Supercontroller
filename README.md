# EP Control Supersocket UCEF Federation
Brian Woo-Shem   
Version 0.20 BETA    
Updated 2022-10-29

SuperSocket federate combines the former Controller and Socket system, enabling truly scalable simulations to practically any number of EP building models. This is a work-in-progress refactoring of "Scalable Simulation" and "EnergyPlusOccOpt2Fed\_v5-6\_Stable". 100% of code has been rewritten by Brian. 

## Capabilities

- Run Fixed, Adaptive, & Occupancy simulations
- Occupancy data is loaded in Java using custom-built CSV conversion code
- Occupancy probability is handled inside Java without Python, using a nearest-percent approximation for comfort range expansion.
- Scalable to practically any number of simulations without rebuilding Java code (within computing power limits)
- Easy to add another simulation - simply change the config files!
- Potential for human error virtually eliminated: 0 weather files, 0 pricing files, all weather data directly from EP, config files are differentiated by simulation location, duration, and control type.
- Get up to 1 year of occupancy data from "OccupancyAnnualHourly.csv". Occupancy loopback (if not enough occupancy data is present) is implemented.
- Merged with Hannah's Appliance Scheduler v2.2 -> upgraded to multisim arrays

## Quick instructions

1. If this is the first time using this system on this computer, go to "generated" folder, open terminal, type `bash build-all.sh`
2. In "deployment" folder:  
	a. Open `setNumSims.txt`: Should have only a single integer on the first line. Set this to the number of sims. Save + close   
	b. Open `config.txt`: Right now the only things that need to be changed are IP to match UCEF VM, and building names. This is a list, one per simulation.  
	c. Create or open `config_buildingName.txt` files for each building. Use `config_demo1.txt` as a template.   
	__MODE:__ type of HVAC control. "fixed" "adaptive90" or "occupancy"   
	__optimize__ Must remain false; not implemented yet   
	__thermostat\_code\_language:__ "java" is implemented and tested for the 3 modes. "python3" is implemented but not tested so use at your own risk   
	__dishwasher:__ Appliance scheduler is depricated but it might work; it is safest to set to "false"   
	__occupancy\_dataset:__ is not implemented yet. All buildings default to "OccupancyAnnualHourly.csv"   
	d. run in terminal `bash run-default.sh ../EP_Control_generated`  
3. Make sure that config.txt, config_buildingName.txt, and OccupancyAnnualHourly.csv are in the "Deployment" folder.
4. Use the [FMU template](https://github.com/SCU-Smart-Grid-CPS/Energy-Plus-Co-Sim-Models/releases/tag/FMU) for up to 32 simulations with consecutive port numbers. Put UCEF-compatible EP files in each numbered subfolder, and check each simulations config file. Some models are available in [EnergyPlus Co-Sim Models](https://github.com/SCU-Smart-Grid-CPS/Energy-Plus-Co-Sim-Models)
5. Launch EP sims from Windows VM. Change the port number in the `config.txt` in `Joe_ep_fmu.fmu` if needed; typically the first simulation is 6789 and the subsequent simulations are +1 each. Careful to match the order they are listed inside the UCEF config file.


## Warnings

1. This has only undergone basic testing, use at your own risk!
2. Appliance scheduler is depricated but it might work; it is safest to leave dishwasher=false in config_buildingName.txt
3. Optimization is not yet implemented
4. CAREFUL with config files. There is `config.txt` AND `config_simulationName.txt` for EACH simulation
5. `setNumSims.txt` must contain a single line with just one integer which is the number of sims
6. Suspect 2 timestep delay, one each direction from EP. Previous stable version had adaptive computed with 1 timestep delay by pulling in data from csv files instead of EP, but the feedback on indoor temperature was 2 timesteps delayed.
