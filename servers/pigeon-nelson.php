<?php 

/*
  Dependancies:

  * https://github.com/jsor/geokit (version 1.3.0)

 */
require __DIR__ . "/vendor/autoload.php";

use Geokit\LatLng;



abstract class PNUtil {
    public static $math;
    
    public static function osm2geokit($element) {
        if ($element["type"] == "node") {
            return new Geokit\LatLng($element["lat"], $element["lon"]);
        }
        else 
        if ($element["center"] != null) {
            return new Geokit\LatLng($element["center"]["lat"], $element["center"]["lon"]);
        }
    }


    public static function degreeToClock($azimuth) {
        return $azimuth / 360 * 12;
    }

    private static function fmod_alt($x, $y) {
        if (!$y) { return NAN; }
        $r = fmod($x, $y);
        if ($r < 0)
            return $r += $y;
        else
            return $r;
    }
    
    public static function clockDistance($h1, $h2) {
        return PNUtil::fmod_alt(abs($h1 - $h2), 12);
    }
    
    public static function geoDistanceKm($distance) {
        return new Geokit\Distance($distance, Geokit\Distance::UNIT_KILOMETERS);
    }
    
    public static function distance($position1, $position2) {
        return PNUtil::$math->distanceHaversine($position1, $position2);
    }

};

PNUtil::$math = new Geokit\Math();


abstract class Comparison
{
    const lessThan = 0;
    const lessOrEqualTo = 1;
    const greaterThan = 2;
    const greaterOrEqualTo = 3;

    public static function toString($comparison) {
        switch($comparison) {
            case Comparison::lessThan:
                return "<";
                break;
            case Comparison::lessOrEqualTo:
                return "<=";
                break;
            case Comparison::greaterThan:
                return ">";
                break;
            case Comparison::greaterOrEqualTo:
                return ">=";
                break;
            default:
                return "";
        }
                
    }
}

class PigeonNelsonCondition {

    public function __construct($reference, $comparison, $parameter) {
        $this->reference = $reference;
        $this->comparison = $comparison;
        $this->parameter = $parameter;
    }
    
    public static function ConditionDistanceTo($coordinates, $comparison, $parameter) {
        return new PigeonNelsonCondition("distanceTo(" . $coordinates. ")", $comparison, $parameter);
    }
    
    public function toString() {
        return '{"reference": "' . $this->reference . '", "comparison": "'. Comparison::toString($this->comparison) . '", "parameter": "' . $this->parameter .'" }';
    }

};


class PigeonNelsonMessage {
    private $txt;
    private $lang;
    private $audioURL;
    private $priority;
    private $requiredConditions;
    private $forgettingConditions;
    
    public function __construct() {
        $this->txt = null;
        $this->lang = null;
        $this->audioURL = null;
        $this->priority = 1;
        $this->requiredConditions = [];
        $this->forgettingConditions = [];
    }
    
    public static function makeTxtMessage($txt, $lang) {
        $result = new PigeonNelsonMessage();
        $result->txt = $txt;
        $result->lang = $lang;
        return $result;
    }
    public static function makeAudioMessage($audioURL) {
        $result = new PigeonNelsonMessage();
        $result->audioURL = $audioURL;
        return $result;
    }
    public function setPriority($priority) {
        $this->priority = $priority;
    }
    public function addRequiredCondition($condition) {
        array_push($this->requiredConditions, $condition);
    }
    public function addForgettingCondition($condition) {
        array_push($this->forgettingConditions, $condition);
    }
    
    public function toString() {
        $result = '{';
        if ($this->txt != null) {
            $result .= '"txt": "' . $this->txt . '",';
        }
        if ($this->lang != null) {
            $result .= '"lang": "' . $this->lang . '",';
        }
        if ($this->audioURL != null) {
            $result .= '"audioURL": "' . $this->audioURL . '",';
        }
        if ($this->priority != null) {
            $result .= '"priority": '. $this->priority .',';
        }
        $result .= '"requiredConditions": [';
        $first = true;
        foreach($this->requiredConditions as $condition) {
            if ($first) $first = false;
            else $result .= ", ";
            $result .= $condition->toString();
        }
        $result .= '], ';
        $result .= '"forgettingConditions": [';
        foreach($this->forgettingConditions as $condition) {
            if ($first) $first = false;
            else $result .= ", ";
            $result .= $condition.toString();
        }
        $result .= ']}';
        
        return $result;
    }
        
}

class PigeonNelsonServer {
    public $requestedLatitude;
    public $requestedLongitude;
    public $requestedAzimuth;
    
    public function __construct($get) {
        if (array_key_exists("lat", $get))
            $this->requestedLatitude = $get["lat"];
        else
            $this->requestedLatitude = null;
        if (array_key_exists("lng", $get))
            $this->requestedLongitude = $get["lng"];
        else
            $this->requestedLongitude = null;
        if (array_key_exists("azimuth", $get))
            $this->requestedAzimuth = $get["azimuth"];
        else
            $this->requestedAzimuth = null;
        $this->data = [];
    }
    
    

    public function hasRequestedAzimuth() {
        return $this->requestedAzimuth != null;
    }
    
    public function hasRequestCoordinates() {
        return $this->requestedLatitude != null && $this->requestedLongitude != null;
    }
    
    private static function replaceBoxInRequest($request, $box_str) {
        $result = str_replace("{{box}}", $box_str, $request);
        $result = str_replace(" ", "%20", $result);
        $result = str_replace("\"", "%22", $result);
        return $result;
    }
    
    public function getRequestedPosition() {
        return new Geokit\LatLng($this->requestedLatitude, $this->requestedLongitude);
    }
    
    public function getRequestedAzimuthAsClock() {
        return PNUtil::degreeToClock($this->requestedAzimuth);
    }
    
    
    private function getBBoxStringFromRequestedPosition($radius) {
        $position =  $this->getRequestedPosition();
        $box = PNUtil::$math->expand($position, $radius . 'km');
        return $box->getSouthWest() . ",". $box->getNorthEast();
    }
    
    public function getOSMData($request, $radius) {
        // create a bounding box from the given position
        
        $box_str = $this->getBBoxStringFromRequestedPosition($radius);
        
        // build request
        $overpass = 'http://overpass-api.de/api/interpreter?data=' . PigeonNelsonServer::replaceBoxInRequest($request, $box_str);
        
        
        // collecting results in JSON format
        $html = file_get_contents($overpass);
        $result = json_decode($html, true); // "true" to get PHP array instead of an object

        // set internal data
        $this->data = $result['elements'];

    }
    
    public function hasEntries() {
        return $this->data != null && count($this->data) != 0;
    }
    
    public function getEntries() {
        return $this->data;
    }
    

};


?>

