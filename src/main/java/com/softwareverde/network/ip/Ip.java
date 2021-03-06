package com.softwareverde.network.ip;

import java.net.*;

public interface Ip {
    static Ip fromSocket(final Socket socket) {
        final SocketAddress socketAddress = socket.getRemoteSocketAddress();
        if (socketAddress instanceof InetSocketAddress) {
            final InetAddress inetAddress = ((InetSocketAddress) socketAddress).getAddress();
            if (inetAddress instanceof Inet4Address) {
                final Inet4Address inet4Address = (Inet4Address) inetAddress;
                return Ipv4.fromBytes(inet4Address.getAddress());
            }
            else if (inetAddress instanceof Inet6Address) {
                final Inet6Address inet6Address = (Inet6Address) inetAddress;
                return Ipv6.fromBytes(inet6Address.getAddress());
            }
        }

        return null;
    }

    static Ip fromString(final String string) {
        if (string == null) { return null; }
        if (string.matches("[^0-9:.]")) { return null; }

        final Boolean isIpv4 = string.matches("^[0-9]+.[0-9]+.[0-9]+.[0-9]+$");
        if (isIpv4) {
            return Ipv4.parse(string);
        }
        else {
            return Ipv6.parse(string);
        }
    }

    static Ip fromHostName(final String hostName) {
        try {
            final InetAddress inetAddress = InetAddress.getByName(hostName);
            if (inetAddress instanceof Inet4Address) {
                final Inet4Address inet4Address = (Inet4Address) inetAddress;
                return Ipv4.fromBytes(inet4Address.getAddress());
            }
            else if (inetAddress instanceof Inet6Address) {
                final Inet6Address inet6Address = (Inet6Address) inetAddress;
                return Ipv6.fromBytes(inet6Address.getAddress());
            }
        }
        catch (final UnknownHostException exception) { }

        return null;
    }

    byte[] getBytes();
    Ip copy();

    @Override
    String toString();
}
