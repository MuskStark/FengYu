package fan.summer.fengyu.plugin.offlinepython.command;

/** One installable wheel for a package, parsed from PyPI's JSON API. */
public record WheelInfo(String version, String platformTag, long sizeBytes, String filename) {}
