# thermostat.py
# Author(s):    Brian Woo-Shem
# Version:      2.0  |  Simulation Version: Supercontroller
# Last Updated: 2022-10-14
# Changelog:
# - Working non-optimizing adaptive and/or occupancy-based setpoints.
# - Based on original occupancyAdaptSetpoints.py but removed many matrices/arrays
# Usage:
#   Typically run from supercontroller.java in UCEF EP_Control. Will be run each 5min timestep
#   For debugging, can run as python script. In folder where this is stored:
#   See "ACCEPT INPUT PARAMETERS" section for run code line

# Import Packages ---------------------------------------------------------------
#import time
#import numpy as np
import sys
from scipy.stats import norm
from configparser import ConfigParser


# IMPORTANT PARAMETERS TO CHANGE ------------------------------------------------

# ===> WHEN TO RUN <=== CHECK IT MATCHES EP!!!
# Designate in [PARAMETERS]
date_range = '2020-08-01_2020-08-31' #failsafe if parameter is blank

# Location
loc = "Default"

# Wholesale Type
# 'r' = real-time
# 'd' = day-ahead
# 'n' = none
priceType = 'n'

# ===> SET HEATING VS COOLING! <===
# OR can instead designate in [PARAMETERS]
#   'heat': only heater, use in winter
#   'cool': only AC, use in summer
heatorcool = 'cool'

# ===> MODE <===
# OR can instead designate in [PARAMETERS]
#   'occupancy': the primary operation mode. Optimization combining probability data and current occupancy status
#   'occupancy_prob': optimization with only occupancy probability (NOT current status)
#   'occupancy_sensor': optimization with only occupancy sensor data for current occupancy status
#   'adaptive90': optimization with adaptive setpoints where 90% people are comfortable. No occupancy
#   'fixed': optimization with fixed setpoints. No occupany.
MODE = 'occupancy'
occupancy_status=0
occ_prob=0.0
indoorTemp=99.9
outdoorTemp=0.01

# ===> Human Readable Output (HRO) SETTING <===
# Extra outputs when testing manually in python or terminal
# These may not be recognized by UCEF Controller.java so HRO = False when running full simulations
HRO = False



# ACCEPT INPUT PARAMETERS ----------------------------------------------------------------------
# From UCEF or command line
# Run as:
#           python occupancyAdaptSetpoints.py indoorTemp=[double] outdoorTemp=[double] MODE=[string] heatOrCool=[string || char]
# Parameters can go in any order
#   Note: Linux may use 'python3' or 'python3.9' instead of 'python'
#         Windows use 'py'
# Optional parameters:
#   date_range=[str]
#	-v || -debug || -HRO  # extra debug outputs
#	-java || -s  # suppress debug outputs
#	price=[string]
#	loc=[string]  # location
#	occupancyStatus=[0||1]  #required for occupancy

# Constants & Indices that should not be changed
ns = len(sys.argv)
i = 1
nf = 0


# Get parameter inputs from command line --------------------------------
while i < ns:
	if "indoorTemp" in sys.argv[i]:
		try: indoorTemp = float(sys.argv[i].replace("indoorTemp=",""))
		except ValueError:
			print('Warning:')
	elif "outdoorTemp" in sys.argv[i]:
		try: outdoorTemp = float(sys.argv[i].replace("outdoorTemp=",""))
		except ValueError:
			print('Warning:')
	elif "occupancyProb" in sys.argv[i]:
		try: occ_prob = float(sys.argv[i].replace("occupancyProb=",""))
		except ValueError:
			print('Warning:')
	elif "occupancyStatus" in sys.argv[i]:
		try: occupancy_status = float(sys.argv[i].replace("occupancyStatus=",""))
		except ValueError:
			print('Warning:')
	elif "heatOrCool=" in sys.argv[i]:
		heatorcool = sys.argv[i].replace("heatOrCool=","")
	elif "loc=" in sys.argv[i]:
		loc = sys.argv[i].replace("loc=","")
	elif "MODE=" in sys.argv[i]:
		MODE = sys.argv[i].replace("MODE=","")
	elif "date=" in sys.argv[i]:
		date_range = sys.argv[i].replace("date=","")
	elif "-v" in sys.argv[i] or "-HRO" in sys.argv[i] or "-debug" in sys.argv[i] : HRO = True
	elif "-java" in sys.argv[i] or "-s" in sys.argv[i]: HRO = False
	elif "price" in sys.argv[i]:
		if "=d" in sys.argv[i]: priceType = 'd'
		elif "=r" in sys.argv[i]: priceType = 'r'
		elif "E-1" in sys.argv[i]: priceType = 'E-1'
		elif "E-TOU-C_S" in sys.argv[i]: priceType = 'E-TOU-C_Summer'
		elif "E-TOU-C_W" in sys.argv[i]: priceType = 'E-TOU-C_Winter'
		elif "E-TD-Z" in sys.argv[i]: priceType = 'E-TD-Z'
		elif "=n" in sys.argv[i]: priceType = 'n'
		else: 
			if HRO:
				print('Warning: invalid wholesale price type, using default, ', priceType, ' instead.')
	else: 
		if HRO: print('Warning: Unrecognized parameter \"', sys.argv[i], '\" Using defaults instead.')
	i += 1

# Print HRO Header
if HRO:
    import datetime as datetime
    print()
    print('=========== thermostat.py V2.0 ===========')
    print('@ ' + datetime.datetime.today().strftime("%Y-%m-%d_%H:%M:%S") + '   Status: RUNNING')

# Compute Adaptive Setpoints ---------------------------------------------------------------
# OK to remove if MODE != 'fixed' on your personal version only if the fixed mode is never used. Keep in master
if MODE != 'fixed':
	# Max and min for heating and cooling in adaptive setpoint control for 90% of people [°C]
	HEAT_TEMP_MAX_90 = 26.2
	HEAT_TEMP_MIN_90 = 18.9
	COOL_TEMP_MAX_90 = 30.2
	COOL_TEMP_MIN_90 = 22.9
	adaptiveCool = outdoorTemp*0.31 + 19.8
	adaptiveHeat = outdoorTemp*0.31 + 15.8
	if adaptiveCool<COOL_TEMP_MIN_90: adaptiveCool=COOL_TEMP_MIN_90
	if adaptiveCool>COOL_TEMP_MAX_90: adaptiveCool=COOL_TEMP_MAX_90
	if adaptiveHeat<HEAT_TEMP_MIN_90: adaptiveHeat=HEAT_TEMP_MIN_90
	if adaptiveHeat>HEAT_TEMP_MAX_90: adaptiveHeat=HEAT_TEMP_MAX_90

# Get Occupancy Data & Compute Setpoints if Occupancy mode selected -------------------------
if "occupancy" in MODE:
	# Min and max temperature for heating and cooling adaptive for 100% of people [°C]
	HEAT_TEMP_MAX_100 = 25.7
	HEAT_TEMP_MIN_100 = 18.4
	COOL_TEMP_MAX_100 = 29.7
	COOL_TEMP_MIN_100 = 22.4
	# Furthest setback points allowed when building is unoccupied [°C]
	vacantCool = 32
	vacantHeat = 12

	adaptive_cooling_100 = outdoorTemp*0.31 + 19.3
	adaptive_heating_100 = outdoorTemp*0.31 + 16.3

	# Calculate comfort band
	sigma = 3.937 # This was calculated based on adaptive comfort being normally distributed

	fx = (1-occ_prob)/2 +1/2
	op_comfort_range = norm.ppf(fx)*sigma


	#see opt code for what older version looked like.
	probHeat = adaptive_heating_100-op_comfort_range
	probCool = adaptive_cooling_100+op_comfort_range
	
	if adaptiveCool<COOL_TEMP_MIN_100: adaptiveCool=COOL_TEMP_MIN_100
	if adaptiveCool>COOL_TEMP_MAX_100: adaptiveCool=COOL_TEMP_MAX_100
	if adaptiveHeat<HEAT_TEMP_MIN_100: adaptiveHeat=HEAT_TEMP_MIN_100
	if adaptiveHeat>HEAT_TEMP_MAX_100: adaptiveHeat=HEAT_TEMP_MAX_100

#------------------------ Data Ready! -------------------------

# Compute Heating & Cooling Setpoints ----------------------------------------------------

# Initialize cool and heat setpoints with flag values. These will contain setpoints for the selected MODE.
spCool = 999
spHeat = 0

# Temperature bounds for b matrix depend on MODE ---------------------------------------------- 
# Loop structure is designed to reduce unnecessary computation and speed up program.  
# Once an "if" or "elif" on that level is true, later "elif"s are ignored.
# This outer occupancy if helps when running adaptive or fixed. If only running occupancy, can remove
# outer if statement and untab the inner ones on your personal copy only. Please keep in master.
if 'occupancy' in MODE:
	# Occupany with both sensor and probability
	if MODE == 'occupancy': #For speed, putting this one first because it is the most common.
		# String for displaying occupancy status
		occnow = ''
		# If occupancy is initially true (occupied)
		if occupancy_status == 1:
			occnow = 'OCCUPIED'
			# If occupied initially, use 90% adaptive setpoint for the first occupancy timeframe
			spCool = adaptiveCool
			spHeat = adaptiveHeat
		else: # At this point, k = n_occ = 12 if Occupied,   k = 0 if UNoccupied at t = first_timestep
			# Assume it is UNoccupied at t > first_timestep and use the probabilistic occupancy setpoints
			spCool = probCool
			spHeat = probHeat

	elif MODE == 'occupancy_sensor':
		occnow = ''
		# If occupancy is initially true (occupied)
		if occupancy_status == 1:
			occnow = 'OCCUPIED'
			# If occupied initially, use 90% adaptive setpoint for the first occupancy frame
			spCool = adaptiveCool
			spHeat = adaptiveHeat
		else:
			spCool = vacantCool
			spHeat = vacantHeat

	elif MODE == 'occupancy_prob':
		occnow = 'UNKNOWN'
		spCool = probCool
		spHeat = probHeat
    
# Adaptive setpoint without occupancy
elif MODE == 'adaptive90':
	occnow = 'UNKNOWN'
	spCool = adaptiveCool
	spHeat = adaptiveHeat

# Fixed setpoints - infrequently used, so put last
elif MODE == 'fixed':
	# Fixed setpoints:
	FIXED_UPPER = 23.0
	FIXED_LOWER = 20.0
	occnow = 'UNKNOWN'
	spCool = FIXED_UPPER
	spHeat = FIXED_LOWER

#Shut off whichever is not needed:
if 'c' in heatorcool:
	spHeat = 0.0
elif 'h' in heatorcool:
	spCool = 50.0
else:
	if HRO:
		print('Warning: heatorcool unspecified, keeping both heating and cooling setpoints')

# Human readable output -------------------------------------------------
if HRO:
    print('MODE = ' + MODE)
    print('Date Range: ' + date_range)
    #print('Day = ' + str(day))
    #print('Block = ' + str(block))
    print('Initial Temperature Inside = ' + str(indoorTemp) + ' °C')
    print('Initial Temperature Outdoors = ' + str(outdoorTemp) + ' °C')
    print('Initial Max Temp = ' + str(spCool) + ' °C')
    print('Initial Min Temp = ' + str(spHeat) + ' °C\n')
    if occnow == '':
        occnow = 'VACANT'
    print('Current Occupancy Status: ' + occnow)
    # Detect invalid MODE
    if spCool == 999.9 or spHeat == 999.9:
        print('FATAL ERROR: Invalid mode, check \'MODE\' string. \n\nx x\n >\n ⁔\n')
        print('@ ' + datetime.datetime.today().strftime("%Y-%m-%d_%H:%M:%S") + '   Status: TERMINATING - ERROR')
        print('================================================\n')
        exit()

#Output back to UCEF

print('thermostat_set_heat')
print(spHeat)

print('thermostat_set_cool')
print(spCool)


if HRO:
    # Human-readable footer
    print('\n@ ' + datetime.datetime.today().strftime("%Y-%m-%d_%H:%M:%S") + '   Status: TERMINATING - SETPOINTS SUCCESS')
    print('================================================\n')
