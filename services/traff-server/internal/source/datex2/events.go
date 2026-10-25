package datex2

import (
	"fmt"
	"math"
	"strings"

	"github.com/WayneNani/better-maps/services/traff-server/internal/traff"
)

// eventMapping maps the type-specific enumeration value of one situation record type to TraFF
// event types. def is used for missing or unknown values.
type eventMapping struct {
	def    string
	values map[string]string
}

// recordEvents maps DATEX II situation record types (v2 and v3 names) to TraFF events. Event
// types follow the TraFF event list (https://gitlab.com/traffxml/traff, "TraFF Event List.md");
// the test checks every value against that list.
var recordEvents = map[string]eventMapping{
	"Accident": {"INCIDENT_ACCIDENT", map[string]string{
		"accident":                            "INCIDENT_ACCIDENT",
		"seriousAccident":                     "INCIDENT_SERIOUS_ACCIDENT",
		"multivehicleAccident":                "INCIDENT_MULTI_VEHICLE_ACCIDENT",
		"multipleVehicleCollision":            "INCIDENT_MULTI_VEHICLE_ACCIDENT",
		"accidentInvolvingHeavyLorries":       "INCIDENT_HGV_ACCIDENT",
		"accidentInvolvingHazardousMaterials": "INCIDENT_HAZMAT_ACCIDENT",
		"accidentInvolvingBuses":              "INCIDENT_BUS_ACCIDENT",
		"accidentInvestigationWork":           "INCIDENT_INVESTIGATION",
		"earlierAccident":                     "INCIDENT_EARLIER_ACCIDENT",
		"secondaryAccident":                   "INCIDENT_SECONDARY_ACCIDENT",
		"vehicleSpunAround":                   "INCIDENT_SPUN_VEHICLE",
		"overturnedHeavyLorry":                "INCIDENT_OVERTURNED_HGV",
		"overturnedVehicle":                   "INCIDENT_OVERTURNED_VEHICLE",
		"jackknifedArticulatedLorry":          "INCIDENT_JACKKNIFED_HGV",
		"jackknifedCaravan":                   "INCIDENT_JACKKNIFED_CARAVAN",
		"jackknifedTrailer":                   "INCIDENT_JACKKNIFED_TRAILER",
		"vehicleOnFire":                       "INCIDENT_VEHICLE_FIRE",
	}},
	"AbnormalTraffic": {"CONGESTION_TRAFFIC_PROBLEM", map[string]string{
		"stationaryTraffic": "CONGESTION_STATIONARY_TRAFFIC",
		"queuingTraffic":    "CONGESTION_QUEUE",
		"slowTraffic":       "CONGESTION_SLOW_TRAFFIC",
		"heavyTraffic":      "CONGESTION_HEAVY_TRAFFIC",
	}},
	"RoadOrCarriagewayOrLaneManagement": {"RESTRICTION_RESTRICTIONS", map[string]string{
		"roadClosed":                     "RESTRICTION_CLOSED",
		"closedPermanentlyForTheWinter":  "RESTRICTION_CLOSED",
		"carriagewayClosures":            "RESTRICTION_CARRIAGEWAY_CLOSED",
		"laneClosures":                   "RESTRICTION_LANE_CLOSED",
		"narrowLanes":                    "RESTRICTION_NARROW_LANES",
		"contraflow":                     "RESTRICTION_CONTRAFLOW",
		"singleAlternateLineTraffic":     "RESTRICTION_SINGLE_ALTERNATE_LINE_TRAFFIC",
		"intermittentShortTermClosures":  "RESTRICTION_INTERMITTENT_CLOSURES",
		"rampClosures":                   "RESTRICTION_RAMP_CLOSED",
		"useOfHardShoulderAllowed":       "RESTRICTION_USE_SHOULDER",
		"hardShoulderRunningInOperation": "RESTRICTION_USE_SHOULDER",
		"newRoadworksLayout":             "CONSTRUCTION_NEW_ROADWORKS_LAYOUT",
		"lanesDeviated":                  "RESTRICTION_TRAFFIC_REGULATIONS_CHANGED",
		"trafficBeingManuallyDirected":   "RESTRICTION_TRAFFIC_DIRECTED_MANUALLY",
	}},
	"MaintenanceWorks": {"CONSTRUCTION_ROADWORKS", map[string]string{
		"roadworks":                    "CONSTRUCTION_ROADWORKS",
		"resurfacingWork":              "CONSTRUCTION_RESURFACING_WORK",
		"repairWork":                   "CONSTRUCTION_REPAIR",
		"maintenanceWork":              "CONSTRUCTION_MAINTENANCE",
		"centralReservationWork":       "CONSTRUCTION_CENTRAL_RESERVATION_WORK",
		"roadworksClearance":           "CONSTRUCTION_ROADWORK_CLEARANCE",
		"blastingWork":                 "CONSTRUCTION_BLASTING",
		"overheadWorks":                "CONSTRUCTION_ROADWORKS",
		"roadMarkingWork":              "HAZARD_ROAD_MARKING_WORK",
		"temporaryTrafficLights":       "CONSTRUCTION_TEMP_TRAFFIC_LIGHT",
		"slowMovingMaintenanceVehicle": "HAZARD_SLOW_MOVING_MAINTENANCE",
	}},
	"Roadworks": {"CONSTRUCTION_ROADWORKS", nil},
	"ConstructionWorks": {"CONSTRUCTION_CONSTRUCTION_WORK", map[string]string{
		"constructionWork": "CONSTRUCTION_CONSTRUCTION_WORK",
		"demolitionWork":   "CONSTRUCTION_DEMOLITION_WORK",
		"roadWidening":     "CONSTRUCTION_ROADWORKS",
	}},
	"VehicleObstruction": {"HAZARD_OBSTRUCTION", map[string]string{
		"brokenDownVehicle":                  "INCIDENT_BROKEN_DOWN_VEHICLE",
		"brokenDownHeavyLorry":               "INCIDENT_BROKEN_DOWN_HGV",
		"brokenDownBus":                      "INCIDENT_BROKEN_DOWN_BUS",
		"damagedVehicle":                     "INCIDENT_BROKEN_DOWN_VEHICLE",
		"vehicleOnFire":                      "INCIDENT_VEHICLE_FIRE",
		"vehicleOnWrongCarriageway":          "HAZARD_WRONG_WAY_DRIVER",
		"vehicleTravellingWrongWay":          "HAZARD_WRONG_WAY_DRIVER",
		"abnormalLoad":                       "HAZARD_ABNORMAL_LOAD",
		"wideLoad":                           "HAZARD_WIDE_LOAD",
		"longLoad":                           "HAZARD_LONG_LOAD",
		"slowVehicle":                        "HAZARD_SLOW_VEHICLE",
		"unlitVehicleOnTheRoad":              "HAZARD_UNLIT_VEHICLE",
		"hazardousMaterialsVehicle":          "HAZARD_HAZMAT",
		"vehicleStuckUnderBridge":            "HAZARD_VEHICLE_STUCK_UNDER_BRIDGE",
		"highSpeedChase":                     "HAZARD_HIGH_SPEED_CHASE",
		"militaryConvoy":                     "HAZARD_MILITARY_CONVOY",
		"convoy":                             "HAZARD_CONVOY",
		"emergencyVehicle":                   "HAZARD_EMERGENCY_VEHICLE",
		"highSpeedEmergencyVehicle":          "HAZARD_HIGH_SPEED_EMERGENCY_VEHICLE",
		"recklessDriver":                     "HAZARD_RECKLESS_DRIVER",
		"objectsFallingFromMovingVehicle":    "HAZARD_OBJECTS_FALLING_FROM_MOVING_VEHICLE",
		"spillageOccurringFromMovingVehicle": "HAZARD_SPILLAGE_FROM_MOVING_VEHICLE",
	}},
	"GeneralObstruction": {"HAZARD_OBSTRUCTION", map[string]string{
		"objectOnTheRoad":             "HAZARD_OBJECT_ON_ROAD",
		"objectsOnTheRoad":            "HAZARD_OBJECT_ON_ROAD",
		"shedLoad":                    "HAZARD_SHED_LOAD",
		"peopleOnRoadway":             "HAZARD_PEOPLE_ON_ROAD",
		"childrenOnRoadway":           "HAZARD_CHILDREN_ON_ROAD",
		"cyclistsOnRoadway":           "HAZARD_CYCLISTS_ON_ROAD",
		"unprotectedAccidentArea":     "HAZARD_UNPROTECTED_ACCIDENT",
		"spillageOnTheRoad":           "HAZARD_SPILLAGE",
		"rescueAndRecoveryWork":       "INCIDENT_RESCUE",
		"emergencyServicesAtScene":    "INCIDENT_RESCUE",
		"sightseersObstructingAccess": "HAZARD_SIGHTSEERS_OBSTRUCTING_ACCESS",
		"houseFire":                   "HAZARD_HOUSE_FIRE",
		"seriousFire":                 "HAZARD_SERIOUS_FIRE",
		"airCrash":                    "HAZARD_AIR_CRASH",
		"railCrash":                   "HAZARD_RAIL_CRASH",
	}},
	"AnimalPresenceObstruction": {"HAZARD_ANIMALS_ON_ROAD", map[string]string{
		"herdOfAnimalsOnTheRoad": "HAZARD_ANIMAL_HERDS_ON_ROAD",
		"largeAnimalsOnTheRoad":  "HAZARD_LARGE_ANIMALS_ON_ROAD",
	}},
	"EnvironmentalObstruction": {"HAZARD_OBSTRUCTION", map[string]string{
		"fallenTrees":      "HAZARD_FALLEN_TREE",
		"flooding":         "HAZARD_FLOODING",
		"flashFloods":      "HAZARD_FLASH_FLOODS",
		"rockfalls":        "HAZARD_ROCKFALLS",
		"landslips":        "HAZARD_LANDSLIPS",
		"mudSlide":         "HAZARD_MUD_SLIDE",
		"avalanches":       "HAZARD_AVALANCHE",
		"forestFire":       "HAZARD_FOREST_FIRE",
		"grassFire":        "HAZARD_GRASS_FIRE",
		"stormDamage":      "HAZARD_STORM_DAMAGE",
		"subsidence":       "HAZARD_SUBSIDENCE",
		"earthquakeDamage": "HAZARD_EARTHQUAKE_DAMAGE",
		"sewerOverflow":    "HAZARD_SEWER_OVERFLOW",
		"smokeOrFumes":     "WEATHER_SMOKE_HAZARD",
	}},
	"InfrastructureDamageObstruction": {"HAZARD_OBSTRUCTION", map[string]string{
		"burstPipe":         "HAZARD_BURST_PIPE",
		"burstWaterMain":    "HAZARD_BURST_WATER_MAIN",
		"collapsedSewer":    "HAZARD_COLLAPSED_SEWER",
		"fallenPowerCables": "HAZARD_FALLEN_POWER_CABLES",
		"gasLeak":           "HAZARD_GAS_LEAK",
		"subsidence":        "HAZARD_SUBSIDENCE",
		"damagedBridge":     "HAZARD_BRIDGE_DAMAGE",
	}},
	"PoorEnvironmentConditions": {"HAZARD_DIFFICULT_CONDITIONS", map[string]string{
		"fog":                 "WEATHER_FOG",
		"denseFog":            "WEATHER_DENSE_FOG",
		"patchyFog":           "WEATHER_PATCHY_FOG",
		"freezingFog":         "WEATHER_FREEZING_FOG",
		"rain":                "WEATHER_RAIN",
		"heavyRain":           "WEATHER_HEAVY_RAIN",
		"showers":             "WEATHER_SHOWERS",
		"snowfall":            "WEATHER_SNOWFALL",
		"heavySnowfall":       "WEATHER_HEAVY_SNOWFALL",
		"hail":                "WEATHER_HAIL",
		"damagingHail":        "WEATHER_DAMAGING_HAIL",
		"sleet":               "WEATHER_SLEET",
		"thunderstorms":       "WEATHER_THUNDERSTORM",
		"blizzard":            "WEATHER_BLIZZARD",
		"blowingSnow":         "WEATHER_BLOWING_SNOW",
		"blowingDust":         "WEATHER_BLOWING_DUST",
		"strongWinds":         "WEATHER_STRONG_WIND",
		"strongGustsOfWind":   "WEATHER_GUSTY_WIND",
		"gales":               "WEATHER_GALES",
		"stormForceWinds":     "WEATHER_STORM",
		"hurricaneForceWinds": "WEATHER_HURRICANE",
		"crosswinds":          "WEATHER_CROSSWIND",
		"tornadoes":           "WEATHER_TORNADO",
		"extremeCold":         "WEATHER_EXTREME_COLD",
		"extremeHeat":         "WEATHER_EXTREME_HEAT",
		"frost":               "WEATHER_FROST",
		"visibilityReduced":   "WEATHER_REDUCED_VISIBILITY",
		"smokeHazard":         "WEATHER_SMOKE_HAZARD",
		"sprayHazard":         "WEATHER_SPRAY_HAZARD",
		"sandStorms":          "WEATHER_SANDSTORM",
		"lowSunGlare":         "WEATHER_LOW_SUN_GLARE",
	}},
	"NonWeatherRelatedRoadConditions": {"HAZARD_BAD_ROAD", map[string]string{
		"oilOnRoad":                  "HAZARD_OIL_ON_ROAD",
		"fuelOnRoad":                 "HAZARD_FUEL_ON_ROAD",
		"petrolOnRoad":               "HAZARD_FUEL_ON_ROAD",
		"mudOnRoad":                  "HAZARD_MUD_ON_ROAD",
		"looseChippings":             "HAZARD_LOOSE_CHIPPINGS",
		"looseSandOnRoad":            "HAZARD_LOOSE_SAND_ON_ROAD",
		"leavesOnRoad":               "HAZARD_LEAVES_ON_ROAD",
		"slipperyRoad":               "HAZARD_SLIPPERY_ROAD",
		"roadSurfaceInPoorCondition": "HAZARD_BAD_ROAD",
	}},
	"WeatherRelatedRoadConditions": {"HAZARD_SLIPPERY_ROAD", map[string]string{
		"blackIce":       "HAZARD_BLACK_ICE",
		"ice":            "HAZARD_ICE",
		"icyPatches":     "HAZARD_ICY_PATCHES",
		"freezingRain":   "HAZARD_FREEZING_RAIN",
		"snowOnTheRoad":  "HAZARD_SNOW_ON_ROAD",
		"packedSnow":     "HAZARD_PACKED_SNOW",
		"freshSnow":      "HAZARD_FRESH_SNOW",
		"deepSnow":       "HAZARD_DEEP_SNOW",
		"snowDrifts":     "HAZARD_SNOW_DRIFTS",
		"slushOnRoad":    "HAZARD_SLUSH",
		"surfaceWater":   "HAZARD_SURFACE_WATER",
		"wetIcyRoad":     "HAZARD_WET_ICY_ROADS",
		"wetAndIcyRoad":  "HAZARD_WET_ICY_ROADS",
		"roadSurfaceWet": "HAZARD_SURFACE_WATER",
		"slipperyRoad":   "HAZARD_SLIPPERY_ROAD",
	}},
	"SpeedManagement": {"RESTRICTION_SPEED_LIMIT", nil},
	"AuthorityOperation": {"HAZARD_POLICE_ACTIVITY", map[string]string{
		"accidentInvestigationWork":   "INCIDENT_INVESTIGATION",
		"policeInvestigation":         "AUTHORITY_INVESTIGATION",
		"policeCheckPoint":            "AUTHORITY_CHECKPOINT",
		"roadOperatorCheckPoint":      "AUTHORITY_CHECKPOINT",
		"vehicleInspectionCheckPoint": "AUTHORITY_CHECKPOINT",
		"customsOperation":            "AUTHORITY_CHECKPOINT",
		"vehicleWeighing":             "AUTHORITY_CHECKPOINT",
		"transportOfVip":              "AUTHORITY_VIP_PASSING",
	}},
}

// supportedType reports whether records of the given xsi:type are mapped.
func supportedType(typ string) bool {
	_, ok := recordEvents[typ]
	return ok
}

func eventClass(typ string) string {
	for _, c := range []string{"AUTHORITY", "CONGESTION", "CONSTRUCTION", "DELAY", "HAZARD", "INCIDENT", "RESTRICTION", "WEATHER"} {
		if strings.HasPrefix(typ, c+"_") {
			return c
		}
	}
	return "" // not reached for the types above
}

func intPtr(v int) *int { return &v }

// recordEventList returns the TraFF events of one situation record. ok is false for record
// types that are not mapped.
func recordEventList(r *Record) (events []traff.Event, ok bool) {
	m, ok := recordEvents[r.Type]
	if !ok {
		return nil, false
	}
	typ := m.def
	if v, found := m.values[r.SubType]; found {
		typ = v
	}
	if r.Type == "SpeedManagement" && r.SpeedLimit == 0 {
		// Speed management without a limit (e.g. variable speed limits switched off) carries no
		// information for routing.
		return nil, false
	}
	ev := traff.Event{Class: eventClass(typ), Type: typ}
	switch typ {
	case "RESTRICTION_LANE_CLOSED":
		if r.LanesRestricted > 0 {
			ev.QInt = intPtr(r.LanesRestricted)
		}
	case "RESTRICTION_SPEED_LIMIT":
		ev.Speed = intPtr(r.SpeedLimit)
	}
	events = append(events, ev)
	if r.DelaySeconds > 0 {
		mins := int(math.Ceil(r.DelaySeconds / 60))
		events = append(events, traff.Event{Class: "DELAY", Type: "DELAY_DELAY", QDuration: fmt.Sprintf("%02d:%02d", mins/60, mins%60)})
	}
	return events, true
}
