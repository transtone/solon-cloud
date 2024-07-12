package org.noear.solon.cloud.extend.consul.service;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.kv.model.GetValue;
import org.noear.solon.Solon;
import org.noear.solon.Utils;
import org.noear.solon.cloud.CloudConfigHandler;
import org.noear.solon.cloud.CloudProps;
import org.noear.solon.cloud.model.Config;
import org.noear.solon.cloud.service.CloudConfigObserverEntity;
import org.noear.solon.cloud.service.CloudConfigService;
import org.noear.solon.cloud.utils.IntervalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 云端配置服务实现
 *
 * @author 夜の孤城
 * @author noear
 * @since 1.2
 */
public class CloudConfigServiceConsulImpl extends TimerTask implements CloudConfigService {
    static final Logger log = LoggerFactory.getLogger(CloudConfigServiceConsulImpl.class);

    private final String DEFAULT_GROUP = "DEFAULT_GROUP";

    private ConsulClient client;
    private String token;

    private long refreshInterval;

    private Map<String, Config> configMap = new HashMap<>();
    private List<CloudConfigObserverEntity> observerList = new ArrayList<>();

    /**
     * 初始化客户端
     */
    private void initClient(String server) {
        String[] ss = server.split(":");

        if (ss.length == 1) {
            client = new ConsulClient(ss[0]);
        } else {
            client = new ConsulClient(ss[0], Integer.parseInt(ss[1]));
        }
    }

    public CloudConfigServiceConsulImpl(CloudProps cloudProps) {
        token = cloudProps.getToken();
        refreshInterval = IntervalUtils.getInterval(cloudProps.getConfigRefreshInterval("5s"));

        initClient(cloudProps.getConfigServer());
    }

    public long getRefreshInterval() {
        return refreshInterval;
    }

    /**
     * 获取配置
     */
    @Override
    public Config pull(String group, String key) {
        if (Utils.isEmpty(group)) {
            group = Solon.cfg().appGroup();

            if (Utils.isEmpty(group)) {
                group = DEFAULT_GROUP;
            }
        }

        String cfgKey = group + "/" + key;

        GetValue newV = client.getKVValue(cfgKey, token).getValue();

        if (newV != null) {
            Config oldV = configMap.get(cfgKey);

            if (oldV == null) {
                oldV = new Config(group, key, newV.getDecodedValue(), newV.getModifyIndex());
                configMap.put(cfgKey, oldV);
            } else if (newV.getModifyIndex() > oldV.version()) {
                oldV.updateValue(newV.getDecodedValue(), newV.getModifyIndex());
            }

            return oldV;
        } else {
            return null;
        }
    }


    @Override
    public boolean push(String group, String key, String value) {
        if (Utils.isEmpty(group)) {
            group = Solon.cfg().appGroup();

            if (Utils.isEmpty(group)) {
                group = DEFAULT_GROUP;
            }
        }

        String cfgKey = group + "/" + key;

        return client.setKVValue(cfgKey, value).getValue();
    }

    @Override
    public boolean remove(String group, String key) {
        if (Utils.isEmpty(group)) {
            group = Solon.cfg().appGroup();

            if (Utils.isEmpty(group)) {
                group = DEFAULT_GROUP;
            }
        }

        String cfgKey = group + "/" + key;

        client.deleteKVValue(cfgKey).getValue();
        return true;
    }

    @Override
    public void attention(String group, String key, CloudConfigHandler observer) {
        if (Utils.isEmpty(group)) {
            group = Solon.cfg().appGroup();

            if (Utils.isEmpty(group)) {
                group = DEFAULT_GROUP;
            }
        }

        observerList.add(new CloudConfigObserverEntity(group, key, observer));
    }

    @Override
    public void run() {
        try {
            run0();
        } catch (Throwable e) {
            log.warn(e.getMessage(), e);
        }
    }

    private void run0() {
        Map<String, Config> cfgTmp = new HashMap<>();
        for (CloudConfigObserverEntity entity : observerList) {
            String cfgKey = entity.group + "/" + entity.key;

            GetValue newV = client.getKVValue(cfgKey, token).getValue();

            if (newV != null) {
                Config oldV = configMap.get(cfgKey);

                if (oldV == null) {
                    oldV = new Config(entity.group, entity.key, newV.getDecodedValue(), newV.getModifyIndex());
                    configMap.put(cfgKey, oldV);
                    cfgTmp.put(cfgKey, oldV);
                } else if (newV.getModifyIndex() > oldV.version()) {
                    oldV.updateValue(newV.getDecodedValue(), newV.getModifyIndex());
                    cfgTmp.put(cfgKey, oldV);
                }
            }
        }

        for (Config cfg2 : cfgTmp.values()) {
            for (CloudConfigObserverEntity entity : observerList) {
                if (cfg2.group().equals(entity.group) && cfg2.key().equals(entity.key)) {
                    entity.handler.handle(cfg2);
                }
            }
        }
    }
}