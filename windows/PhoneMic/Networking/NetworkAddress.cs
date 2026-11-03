using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace PhoneMic.Networking;

internal static class NetworkAddress
{
    private static readonly string[] VirtualAdapterKeywords =
    [
        "virtual",
        "vmware",
        "virtualbox",
        "hyper-v",
        "vethernet",
        "tailscale",
        "zerotier",
        "wireguard",
        "vpn",
        "tap-windows",
        "openvpn",
        "loopback",
        "npcap",
        "wsl",
        "docker",
        "bluetooth",
        "hamachi",
        "radmin",
    ];

    /// <summary>
    /// Returns the IPv4 address Windows should advertise to the phone.
    /// Virtual/VPN adapters are ignored so users don't copy an unusable address.
    /// </summary>
    public static string? GetPreferredLocalIPv4()
    {
        var candidates = new List<Candidate>();

        try
        {
            foreach (NetworkInterface adapter in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (adapter.OperationalStatus != OperationalStatus.Up ||
                    adapter.IsReceiveOnly)
                {
                    continue;
                }

                if (adapter.NetworkInterfaceType is NetworkInterfaceType.Loopback
                    or NetworkInterfaceType.Tunnel)
                {
                    continue;
                }

                if (adapter.NetworkInterfaceType is not (
                    NetworkInterfaceType.Wireless80211 or
                    NetworkInterfaceType.Ethernet or
                    NetworkInterfaceType.GigabitEthernet or
                    NetworkInterfaceType.FastEthernetT or
                    NetworkInterfaceType.FastEthernetFx or
                    NetworkInterfaceType.Ethernet3Megabit))
                {
                    continue;
                }

                string adapterText = $"{adapter.Name} {adapter.Description}";
                if (VirtualAdapterKeywords.Any(keyword =>
                    adapterText.Contains(keyword, StringComparison.OrdinalIgnoreCase)))
                {
                    continue;
                }

                IPInterfaceProperties properties;
                try
                {
                    properties = adapter.GetIPProperties();
                }
                catch (NetworkInformationException)
                {
                    continue;
                }

                bool hasGateway = properties.GatewayAddresses.Any(gateway =>
                    gateway.Address.AddressFamily == AddressFamily.InterNetwork &&
                    !gateway.Address.Equals(IPAddress.Any));

                foreach (UnicastIPAddressInformation unicast in properties.UnicastAddresses)
                {
                    IPAddress address = unicast.Address;
                    if (address.AddressFamily != AddressFamily.InterNetwork ||
                        IPAddress.IsLoopback(address) ||
                        address.Equals(IPAddress.Any))
                    {
                        continue;
                    }

                    byte[] bytes = address.GetAddressBytes();
                    // 169.254.x.x is an APIPA address, which means DHCP failed.
                    if (bytes[0] == 169 && bytes[1] == 254)
                    {
                        continue;
                    }

                    candidates.Add(new Candidate(
                        address,
                        hasGateway,
                        adapter.NetworkInterfaceType));
                }
            }
        }
        catch (NetworkInformationException)
        {
            return null;
        }

        return candidates
            .OrderByDescending(candidate => candidate.HasGateway)
            .ThenByDescending(candidate =>
                candidate.AdapterType == NetworkInterfaceType.Wireless80211)
            .Select(candidate => candidate.Address.ToString())
            .FirstOrDefault();
    }

    private readonly record struct Candidate(
        IPAddress Address,
        bool HasGateway,
        NetworkInterfaceType AdapterType);
}
