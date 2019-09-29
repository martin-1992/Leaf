package com.sankuai.inf.leaf.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public class Utils {
    private static final Logger logger = LoggerFactory.getLogger(Utils.class);

    /**
     * 获取已激活网卡的 IP 地址，默认返回第一个 IP
     * @return
     */
    public static String getIp() {
        String ip;
        try {
            // 获取 IP 地址列表，默认选择第一个 IP
            List<String> ipList = getHostAddress(null);
            // default the first
            ip = (!ipList.isEmpty()) ? ipList.get(0) : "";
        } catch (Exception ex) {
            ip = "";
            logger.warn("Utils get IP warn", ex);
        }
        return ip;
    }

    /**
     * 获取已激活网卡的 IP 地址，默认返回等于 interfaceName 的第一个 IP
     * @param interfaceName
     * @return
     */
    public static String getIp(String interfaceName) {
        String ip;
        interfaceName = interfaceName.trim();
        try {
            List<String> ipList = getHostAddress(interfaceName);
            ip = (!ipList.isEmpty()) ? ipList.get(0) : "";
        } catch (Exception ex) {
            ip = "";
            logger.warn("Utils get IP warn", ex);
        }
        return ip;
    }

    /**
     * 获取已激活网卡的 IP 地址
     *
     * @param interfaceName 可指定网卡名称, null 则获取全部
     * @return List<String>
     */
    private static List<String> getHostAddress(String interfaceName) throws SocketException {
        List<String> ipList = new ArrayList<String>(5);
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

        // 遍历所有网络接口，获取 ip 地址，跳过 loopback、IPv6 的地址，
        // 然后判断该 IP 地址名是否等于 interfaceName，为 null 则全部添加
        while (interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            Enumeration<InetAddress> allAddress = ni.getInetAddresses();
            while (allAddress.hasMoreElements()) {
                InetAddress address = allAddress.nextElement();
                if (address.isLoopbackAddress()) {
                    // skip the loopback addr
                    continue;
                }
                // IPv6 地址跳过
                if (address instanceof Inet6Address) {
                    // skip the IPv6 addr
                    continue;
                }
                String hostAddress = address.getHostAddress();
                // 为空则全部添加
                if (null == interfaceName) {
                    ipList.add(hostAddress);
                } else if (interfaceName.equals(ni.getDisplayName())) {
                    // 添加等于 interfaceName 的 ip 地址
                    ipList.add(hostAddress);
                }
            }
        }
        return ipList;
    }
}
