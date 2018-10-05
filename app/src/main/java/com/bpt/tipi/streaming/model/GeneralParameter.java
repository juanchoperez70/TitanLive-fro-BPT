package com.bpt.tipi.streaming.model;

/**
 * Created by jpujolji on 7/03/18.
 */

public class GeneralParameter {

    public String wowzaServerUrl, wowzaAppName, wowzaUser, wowzaPwd;
    public int wowzaPort;
    public int id = 1;

    public GeneralParameter(String wowzaServerUrl, int wowzaPort, String wowzaAppName, String wowzaUser, String wowzaPwd) {
        this.wowzaServerUrl = wowzaServerUrl;
        this.wowzaPort = wowzaPort;
        this.wowzaAppName = wowzaAppName;
        this.wowzaUser = wowzaUser;
        this.wowzaPwd = wowzaPwd;
    }
}
