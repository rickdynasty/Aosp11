"""Controller for Open WRT access point."""

import time

from acts import logger
from acts.controllers.ap_lib import hostapd_constants
from acts.controllers.openwrt_lib import network_settings
from acts.controllers.openwrt_lib import wireless_config
from acts.controllers.openwrt_lib import wireless_settings_applier
from acts.controllers.utils_lib.ssh import connection
from acts.controllers.utils_lib.ssh import settings
import yaml

MOBLY_CONTROLLER_CONFIG_NAME = "OpenWrtAP"
ACTS_CONTROLLER_REFERENCE_NAME = "access_points"
OPEN_SECURITY = "none"
PSK_SECURITY = "psk2"
WEP_SECURITY = "wep"
ENT_SECURITY = "wpa2"
OWE_SECURITY = "owe"
SAE_SECURITY = "sae"
ENABLE_RADIO = "0"
PMF_ENABLED = 2
WIFI_2G = "wifi2g"
WIFI_5G = "wifi5g"
DEFAULT_RADIOS = ("radio0", "radio1")


def create(configs):
  """Creates ap controllers from a json config.

  Creates an ap controller from either a list, or a single element. The element
  can either be just the hostname or a dictionary containing the hostname and
  username of the AP to connect to over SSH.

  Args:
    configs: The json configs that represent this controller.

  Returns:
    AccessPoint object

  Example:
    Below is the config file entry for OpenWrtAP as a list. A testbed can have
    1 or more APs to configure. Each AP has a "ssh_config" key to provide SSH
    login information. OpenWrtAP#__init__() uses this to create SSH object.

      "OpenWrtAP": [
        {
          "ssh_config": {
            "user" : "root",
            "host" : "192.168.1.1"
          }
        },
        {
          "ssh_config": {
            "user" : "root",
            "host" : "192.168.1.2"
          }
        }
      ]
  """
  return [OpenWrtAP(c) for c in configs]


def destroy(aps):
  """Destroys a list of AccessPoints.

  Args:
    aps: The list of AccessPoints to destroy.
  """
  for ap in aps:
    ap.close()
    ap.close_ssh()


def get_info(aps):
  """Get information on a list of access points.

  Args:
    aps: A list of AccessPoints.

  Returns:
    A list of all aps hostname.
  """
  return [ap.ssh_settings.hostname for ap in aps]


class OpenWrtAP(object):
  """An AccessPoint controller.

  Attributes:
    ssh: The ssh connection to the AP.
    ssh_settings: The ssh settings being used by the ssh connection.
    log: Logging object for AccessPoint.
    wireless_setting: object holding wireless configuration.
    network_setting: Object for network configuration
  """

  def __init__(self, config):
    """Initialize AP."""
    self.ssh_settings = settings.from_config(config["ssh_config"])
    self.ssh = connection.SshConnection(self.ssh_settings)
    self.log = logger.create_logger(
        lambda msg: "[OpenWrtAP|%s] %s" % (self.ssh_settings.hostname, msg))
    self.wireless_setting = None
    self.network_setting = network_settings.NetworkSettings(
        self.ssh, config["ssh_config"]["host"], self.log)

  def configure_ap(self, wifi_configs, channel_2g, channel_5g):
    """Configure AP with the required settings.

    Each test class inherits WifiBaseTest. Based on the test, we may need to
    configure PSK, WEP, OPEN, ENT networks on 2G and 5G bands in any
    combination. We call WifiBaseTest methods get_psk_network(),
    get_open_network(), get_wep_network() and get_ent_network() to create
    dictionaries which contains this information. 'wifi_configs' is a list of
    such dictionaries. Example below configures 2 WiFi networks - 1 PSK 2G and
    1 Open 5G on one AP. configure_ap() is called from WifiBaseTest to
    configure the APs.

    wifi_configs = [
      {
        '2g': {
          'SSID': '2g_AkqXWPK4',
          'security': 'psk2',
          'password': 'YgYuXqDO9H',
          'hiddenSSID': False
        },
      },
      {
        '5g': {
          'SSID': '5g_8IcMR1Sg',
          'security': 'none',
          'hiddenSSID': False
        },
      }
    ]

    Args:
      wifi_configs: list of network settings for 2G and 5G bands.
      channel_2g: channel for 2G band.
      channel_5g: channel for 5G band.
    """
    # generate wifi configs to configure
    wireless_configs = self.generate_wireless_configs(wifi_configs)
    self.wireless_setting = wireless_settings_applier.WirelessSettingsApplier(
        self.ssh, wireless_configs, channel_2g, channel_5g)
    self.wireless_setting.apply_wireless_settings()

  def start_ap(self):
    """Starts the AP with the settings in /etc/config/wireless."""
    self.ssh.run("wifi up")
    time.sleep(9)  # wait for sometime for AP to come up

  def stop_ap(self):
    """Stops the AP."""
    self.ssh.run("wifi down")
    time.sleep(9)  # wait for sometime for AP to go down

  def get_bssids_for_wifi_networks(self):
    """Get BSSIDs for wifi networks configured.

    Returns:
      Dictionary of SSID - BSSID map for both bands.
    """
    bssid_map = {"2g": {}, "5g": {}}
    for radio in ["radio0", "radio1"]:
      ssid_ifname_map = self.get_ifnames_for_ssids(radio)
      if radio == "radio0":
        for ssid, ifname in ssid_ifname_map.items():
          bssid_map["5g"][ssid] = self.get_bssid(ifname)
      elif radio == "radio1":
        for ssid, ifname in ssid_ifname_map.items():
          bssid_map["2g"][ssid] = self.get_bssid(ifname)
    return bssid_map

  def get_ifnames_for_ssids(self, radio):
    """Get interfaces for wifi networks.

    Args:
      radio: 2g or 5g radio get the bssids from.

    Returns:
      dictionary of ssid - ifname mappings.
    """
    ssid_ifname_map = {}
    str_output = self.ssh.run("wifi status %s" % radio).stdout
    wifi_status = yaml.load(str_output.replace("\t", "").replace("\n", ""),
                            Loader=yaml.FullLoader)
    wifi_status = wifi_status[radio]
    if wifi_status["up"]:
      interfaces = wifi_status["interfaces"]
      for config in interfaces:
        ssid = config["config"]["ssid"]
        ifname = config["ifname"]
        ssid_ifname_map[ssid] = ifname
    return ssid_ifname_map

  def get_bssid(self, ifname):
    """Get MAC address from an interface.

    Args:
      ifname: interface name of the corresponding MAC.

    Returns:
      BSSID of the interface.
    """
    ifconfig = self.ssh.run("ifconfig %s" % ifname).stdout
    mac_addr = ifconfig.split("\n")[0].split()[-1]
    return mac_addr

  def generate_wireless_configs(self, wifi_configs):
    """Generate wireless configs to configure.

    Converts wifi_configs from configure_ap() to a list of 'WirelessConfig'
    objects. Each object represents a wifi network to configure on the AP.

    Args:
      wifi_configs: Network list of different security types and bands.

    Returns:
      wireless configuration for openwrt AP.
    """
    num_2g = 1
    num_5g = 1
    wireless_configs = []

    for i in range(len(wifi_configs)):
      if hostapd_constants.BAND_2G in wifi_configs[i]:
        config = wifi_configs[i][hostapd_constants.BAND_2G]
        if config["security"] == PSK_SECURITY:
          wireless_configs.append(
              wireless_config.WirelessConfig("%s%s" % (WIFI_2G, num_2g),
                                             config["SSID"],
                                             config["security"],
                                             hostapd_constants.BAND_2G,
                                             password=config["password"],
                                             hidden=config["hiddenSSID"]))
        elif config["security"] == WEP_SECURITY:
          wireless_configs.append(
              wireless_config.WirelessConfig("%s%s" % (WIFI_2G, num_2g),
                                             config["SSID"],
                                             config["security"],
                                             hostapd_constants.BAND_2G,
                                             wep_key=config["wepKeys"][0],
                                             hidden=config["hiddenSSID"]))
        elif config["security"] == OPEN_SECURITY:
          wireless_configs.append(
              wireless_config.WirelessConfig("%s%s" % (WIFI_2G, num_2g),
                                             config["SSID"],
                                             config["security"],
                                             hostapd_constants.BAND_2G,
                                             hidden=config["hiddenSSID"]))
        elif config["security"] == OWE_SECURITY:
          wireless_configs.append(
              wireless_config.WirelessConfig("%s%s" % (WIFI_2G, num_2g),
                                             config["SSID"],
                                             config["security"],
                                             hostapd_constants.BAND_2G,
                                             hidden=config["hiddenSSID"],
                                             ieee80211w=PMF_ENABLED))
        elif config["security"] == SAE_SECURITY:
          wireless_configs.append(
              wireless_config.WirelessConfig("%s%s" % (WIFI_2G, num_2g),
                                             config["SSID"],
                                             config["security"],
                                             hostapd_constants.BAND_2G,
                                             password=config["password"],
                                             hidden=config["hiddenSSID"],
                                             ieee80211w=PMF_ENABLED))
        elif config["security"] == ENT_SECURITY:
          wireless_configs.append(
              wireless_config.WirelessConfig(
                  "%s%s" % (WIFI_2G, num_2g),
                  config["SSID"],
                  config["security"],
                  hostapd_constants.BAND_2G,
                  radius_server_ip=config["radius_server_ip"],
                  radius_server_port=config["radius_server_port"],
                  radius_server_secret=config["radius_server_secret"],
                  hidden=config["hiddenSSID"]))
        num_2g += 1
      if hostapd_constants.BAND_5G in wifi_configs[i]:
        config = wifi_configs[i][hostapd_constants.BAND_5G]
        if config["security"] == PSK_SECURITY:
          wireless_configs.append(
              wireless_config.WirelessConfig("%s%s" % (WIFI_5G, num_5g),
                                             config["SSID"],
                                             config["security"],
                                             hostapd_constants.BAND_5G,
                                             password=config["password"],
                                             hidden=config["hiddenSSID"]))
        elif config["security"] == WEP_SECURITY:
          wireless_configs.append(
              wireless_config.WirelessConfig("%s%s" % (WIFI_5G, num_5g),
                                             config["SSID"],
                                             config["security"],
                                             hostapd_constants.BAND_5G,
                                             wep_key=config["wepKeys"][0],
                                             hidden=config["hiddenSSID"]))
        elif config["security"] == OPEN_SECURITY:
          wireless_configs.append(
              wireless_config.WirelessConfig("%s%s" % (WIFI_5G, num_5g),
                                             config["SSID"],
                                             config["security"],
                                             hostapd_constants.BAND_5G,
                                             hidden=config["hiddenSSID"]))
        elif config["security"] == OWE_SECURITY:
          wireless_configs.append(
              wireless_config.WirelessConfig("%s%s" % (WIFI_5G, num_5g),
                                             config["SSID"],
                                             config["security"],
                                             hostapd_constants.BAND_5G,
                                             hidden=config["hiddenSSID"],
                                             ieee80211w=PMF_ENABLED))
        elif config["security"] == SAE_SECURITY:
          wireless_configs.append(
              wireless_config.WirelessConfig("%s%s" % (WIFI_5G, num_5g),
                                             config["SSID"],
                                             config["security"],
                                             hostapd_constants.BAND_5G,
                                             password=config["password"],
                                             hidden=config["hiddenSSID"],
                                             ieee80211w=PMF_ENABLED))
        elif config["security"] == ENT_SECURITY:
          wireless_configs.append(
              wireless_config.WirelessConfig(
                  "%s%s" % (WIFI_5G, num_5g),
                  config["SSID"],
                  config["security"],
                  hostapd_constants.BAND_5G,
                  radius_server_ip=config["radius_server_ip"],
                  radius_server_port=config["radius_server_port"],
                  radius_server_secret=config["radius_server_secret"],
                  hidden=config["hiddenSSID"]))
        num_5g += 1

    return wireless_configs

  def get_wifi_network(self, security=None, band=None):
    """Return first match wifi interface's config.

    Args:
      security: psk2 or none
      band: '2g' or '5g'

    Returns:
      A dict contains match wifi interface's config.
    """

    for wifi_iface in self.wireless_setting.wireless_configs:
      match_list = []
      wifi_network = wifi_iface.__dict__
      if security:
        match_list.append(security == wifi_network["security"])
      if band:
        match_list.append(band == wifi_network["band"])

      if all(match_list):
        wifi_network["SSID"] = wifi_network["ssid"]
        if not wifi_network["password"]:
          del wifi_network["password"]
        return wifi_network
    return None

  def get_wifi_status(self, radios=DEFAULT_RADIOS):
    """Check if radios are up. Default are 2G and 5G bands.

    Args:
      radios: Wifi interfaces for check status.
    Returns:
      True if both radios are up. False if not.
    """
    status = True
    for radio in radios:
      str_output = self.ssh.run("wifi status %s" % radio).stdout
      wifi_status = yaml.load(str_output.replace("\t", "").replace("\n", ""),
                              Loader=yaml.FullLoader)
      status = wifi_status[radio]["up"] and status
    return status

  def verify_wifi_status(self, radios=DEFAULT_RADIOS, timeout=20):
    """Ensure wifi interfaces are ready.

    Args:
      radios: Wifi interfaces for check status.
      timeout: An integer that is the number of times to try
               wait for interface ready.
    Returns:
      True if both radios are up. False if not.
    """
    start_time = time.time()
    end_time = start_time + timeout
    while time.time() < end_time:
      if self.get_wifi_status(radios):
        return True
      time.sleep(1)
    return False

  def close(self):
    """Reset wireless and network settings to default and stop AP."""
    if self.network_setting.config:
      self.network_setting.cleanup_network_settings()
    if self.wireless_setting:
      self.wireless_setting.cleanup_wireless_settings()

  def close_ssh(self):
    """Close SSH connection to AP."""
    self.ssh.close()
