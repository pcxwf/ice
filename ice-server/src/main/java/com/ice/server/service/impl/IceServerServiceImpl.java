package com.ice.server.service.impl;

import com.alibaba.fastjson.JSON;
import com.ice.common.dto.IceBaseDto;
import com.ice.common.dto.IceConfDto;
import com.ice.common.dto.IceTransferDto;
import com.ice.common.enums.StatusEnum;
import com.ice.server.dao.mapper.IceAppMapper;
import com.ice.server.dao.mapper.IceBaseMapper;
import com.ice.server.dao.mapper.IceConfMapper;
import com.ice.server.dao.model.IceBase;
import com.ice.server.dao.model.IceBaseExample;
import com.ice.server.dao.model.IceConf;
import com.ice.server.dao.model.IceConfExample;
import com.ice.server.constant.Constant;
import com.ice.server.service.IceServerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.*;

/**
 * @author zjn
 */
@Slf4j
@Service
public class IceServerServiceImpl implements IceServerService, InitializingBean {

    private static final Set<Integer> appSet = new HashSet<>();

    private static final Object LOCK = new Object();
    /*
     * key:app value baseList
     */
    private final Map<Integer, Map<Long, IceBase>> baseActiveMap = new HashMap<>();

    private final Map<Integer, Map<Long, Set<Long>>> fromSetMap = new HashMap<>();
    private final Map<Integer, Map<Long, Set<Long>>> toSetMap = new HashMap<>();
    /*
     * key:app value conf
     */
    private final Map<Integer, Map<Long, IceConf>> confActiveMap = new HashMap<>();
    private final Map<Integer, Map<Byte, Map<String, Integer>>> leafClassMap = new HashMap<>();
    /*
     * last update base
     */
    private Date lastBaseUpdateTime;
    /*
     * last update conf
     */
    private Date lastConfUpdateTime;
    private volatile long version;
    @Resource
    private IceBaseMapper baseMapper;
    @Resource
    private IceConfMapper confMapper;
    @Resource
    private IceAppMapper iceAppMapper;
    @Resource
    private AmqpTemplate amqpTemplate;


    @Override
    public IceConf getActiveConfById(Integer app, Long confId) {
        Map<Long, IceConf> confMap = confActiveMap.get(app);
        if (!CollectionUtils.isEmpty(confMap)) {
            IceConf conf = confMap.get(confId);
            addLeafClass(app, conf.getType(), conf.getConfName());
            return conf;
        }
        return null;
    }

    @Override
    public Map<String, Integer> getLeafClassMap(Integer app, Byte type) {
        Map<Byte, Map<String, Integer>> map = leafClassMap.get(app);
        if (map != null) {
            return map.get(type);
        }
        return null;
    }

    public void addLeafClass(Integer app, Byte type, String className) {
        if (Constant.isLeaf(type)) {
            Map<Byte, Map<String, Integer>> map = leafClassMap.get(app);
            Map<String, Integer> classMap;
            if (map == null) {
                map = new HashMap<>();
                leafClassMap.put(app, map);
                classMap = new HashMap<>();
                map.put(type, classMap);
                classMap.put(className, 0);
            } else {
                classMap = map.get(type);
                if (classMap == null) {
                    classMap = new HashMap<>();
                    map.put(type, classMap);
                    classMap.put(className, 0);
                } else {
                    classMap.putIfAbsent(className, 0);
                }
            }
            classMap.put(className, classMap.get(className) + 1);
        }
    }

    @Override
    public void updateByEdit() {
        update();
    }

    /*
     * update by schedule
     */
    @Scheduled(fixedDelay = 20000)
    private void update() {
        Date now = new Date();
        Date lastMaxBaseDate = lastBaseUpdateTime;
        Date lastMaxConfDate = lastConfUpdateTime;
        Map<Integer, Set<Long>> deleteConfMap = new HashMap<>(appSet.size());
        Map<Integer, Set<Long>> deleteBaseMap = new HashMap<>(appSet.size());

        Map<Integer, Map<Long, IceConf>> activeChangeConfMap = new HashMap<>(appSet.size());
        Map<Integer, Map<Long, IceBase>> activeChangeBaseMap = new HashMap<>(appSet.size());

        /*find change in db*/
        IceConfExample confExample = new IceConfExample();
        confExample.createCriteria().andUpdateAtGreaterThan(lastConfUpdateTime).andUpdateAtLessThanOrEqualTo(now);
        List<IceConf> confList = confMapper.selectByExample(confExample);
        if (!CollectionUtils.isEmpty(confList)) {
            for (IceConf conf : confList) {
                if (conf.getUpdateAt().after(lastMaxConfDate)) {
                    lastMaxConfDate = conf.getUpdateAt();
                }
                appSet.add(conf.getApp());
                if (conf.getStatus() == StatusEnum.OFFLINE.getStatus()) {
                    /*update offline in db by hand*/
                    deleteConfMap.computeIfAbsent(conf.getApp(), k -> new HashSet<>()).add(conf.getId());
                    continue;
                }
                activeChangeConfMap.computeIfAbsent(conf.getApp(), k -> new HashMap<>()).put(conf.getId(), conf);
            }
        }
        IceBaseExample baseExample = new IceBaseExample();
        baseExample.createCriteria().andUpdateAtGreaterThan(lastBaseUpdateTime).andUpdateAtLessThanOrEqualTo(now);
        List<IceBase> baseList = baseMapper.selectByExample(baseExample);
        if (!CollectionUtils.isEmpty(baseList)) {
            for (IceBase base : baseList) {
                if (base.getUpdateAt().after(lastMaxBaseDate)) {
                    lastMaxBaseDate = base.getUpdateAt();
                }
                appSet.add(base.getApp());
                if (base.getStatus() == StatusEnum.OFFLINE.getStatus()) {
                    /*update offline in db by hand*/
                    deleteBaseMap.computeIfAbsent(base.getApp(), k -> new HashSet<>()).add(base.getId());
                    continue;
                }
                activeChangeBaseMap.computeIfAbsent(base.getApp(), k -> new HashMap<>()).put(base.getId(), base);
            }
        }
        /*update local cache*/
        long updateVersion = updateLocal(deleteBaseMap, deleteConfMap, activeChangeBaseMap,
                activeChangeConfMap);
        /*send update msg to remote client*/
        sendChange(deleteConfMap, deleteBaseMap, activeChangeConfMap, activeChangeBaseMap, updateVersion);
        /*update time (why not update to now? to avoid timeline conflict)*/
        lastBaseUpdateTime = lastMaxBaseDate;
        lastConfUpdateTime = lastMaxConfDate;
    }

    private void sendChange(Map<Integer, Set<Long>> deleteConfMap,
                            Map<Integer, Set<Long>> deleteBaseMap,
                            Map<Integer, Map<Long, IceConf>> activeChangeConfMap,
                            Map<Integer, Map<Long, IceBase>> activeChangeBaseMap,
                            long updateVersion) {
        for (Integer app : appSet) {
            IceTransferDto transferDto = null;
            Map<Long, IceConf> insertOrUpdateConfMap = activeChangeConfMap.get(app);
            if (!CollectionUtils.isEmpty(insertOrUpdateConfMap)) {
                transferDto = new IceTransferDto();
                Collection<IceConfDto> confDtoList = new ArrayList<>(insertOrUpdateConfMap.values().size());
                for (IceConf conf : insertOrUpdateConfMap.values()) {
                    confDtoList.add(Constant.confToDto(conf));
                }
                transferDto.setInsertOrUpdateConfs(confDtoList);
            }
            Map<Long, IceBase> insertOrUpdateBaseMap = activeChangeBaseMap.get(app);
            if (!CollectionUtils.isEmpty(insertOrUpdateBaseMap)) {
                if (transferDto == null) {
                    transferDto = new IceTransferDto();
                }
                Collection<IceBaseDto> baseDtoList = new ArrayList<>(insertOrUpdateBaseMap.values().size());
                for (IceBase base : insertOrUpdateBaseMap.values()) {
                    baseDtoList.add(Constant.baseToDto(base));
                }
                transferDto.setInsertOrUpdateBases(baseDtoList);
            }
            Set<Long> deleteConfIds = deleteConfMap.get(app);
            if (!CollectionUtils.isEmpty(deleteConfMap)) {
                if (transferDto == null) {
                    transferDto = new IceTransferDto();
                }
                transferDto.setDeleteConfIds(deleteConfIds);
            }
            Set<Long> deleteBases = deleteBaseMap.get(app);
            if (!CollectionUtils.isEmpty(deleteBases)) {
                if (transferDto == null) {
                    transferDto = new IceTransferDto();
                }
                transferDto.setDeleteBaseIds(deleteBases);
            }
            /*send update msg to remote client while has change*/
            if (transferDto != null) {
                transferDto.setVersion(updateVersion);
                String message = JSON.toJSONString(transferDto);
                amqpTemplate.convertAndSend(Constant.getUpdateExchange(), Constant.getUpdateRouteKey(app), message);
                log.info("ice update app:{}, content:{}", app, message);
            }
        }
    }

    /*
     * update local cache
     * first handle delete,then insert&update
     */
    private long updateLocal(Map<Integer, Set<Long>> deleteBaseMap,
                             Map<Integer, Set<Long>> deleteConfMap,
                             Map<Integer, Map<Long, IceBase>> activeChangeBaseMap,
                             Map<Integer, Map<Long, IceConf>> activeChangeConfMap) {
        synchronized (LOCK) {
            for (Map.Entry<Integer, Set<Long>> entry : deleteConfMap.entrySet()) {
                for (Long id : entry.getValue()) {
                    Map<Long, IceConf> tmpActiveMap = confActiveMap.get(entry.getKey());
                    if (tmpActiveMap != null) {
                        confActiveMap.get(entry.getKey()).remove(id);
                    }
                }
            }
            for (Map.Entry<Integer, Set<Long>> entry : deleteBaseMap.entrySet()) {
                for (Long id : entry.getValue()) {
                    Map<Long, IceBase> tmpActiveMap = baseActiveMap.get(entry.getKey());
                    if (tmpActiveMap != null) {
                        baseActiveMap.get(entry.getKey()).remove(id);
                    }
                }
            }
            for (Map.Entry<Integer, Map<Long, IceBase>> appEntry : activeChangeBaseMap.entrySet()) {
                for (Map.Entry<Long, IceBase> entry : appEntry.getValue().entrySet()) {
                    baseActiveMap.computeIfAbsent(appEntry.getKey(), k -> new HashMap<>()).put(entry.getKey(), entry.getValue());
                }
            }
            for (Map.Entry<Integer, Map<Long, IceConf>> appEntry : activeChangeConfMap.entrySet()) {
                for (Map.Entry<Long, IceConf> entry : appEntry.getValue().entrySet()) {
                    confActiveMap.computeIfAbsent(appEntry.getKey(), k -> new HashMap<>()).put(entry.getKey(), entry.getValue());
                }
            }
            version++;
            return version;
        }
    }

    @Override
    public void afterPropertiesSet() {
        Date now = new Date();
        /*baseList*/
        IceBaseExample baseExample = new IceBaseExample();
        baseExample.createCriteria().andStatusEqualTo(StatusEnum.ONLINE.getStatus()).andUpdateAtLessThanOrEqualTo(now);
        List<IceBase> baseList = baseMapper.selectByExample(baseExample);

        if (!CollectionUtils.isEmpty(baseList)) {
            for (IceBase base : baseList) {
                appSet.add(base.getApp());
                baseActiveMap.computeIfAbsent(base.getApp(), k -> new HashMap<>()).put(base.getId(), base);
            }
        }
        /*ConfList*/
        IceConfExample confExample = new IceConfExample();
        confExample.createCriteria().andStatusEqualTo(StatusEnum.ONLINE.getStatus()).andUpdateAtLessThanOrEqualTo(now);
        List<IceConf> confList = confMapper.selectByExample(confExample);
        if (!CollectionUtils.isEmpty(confList)) {
            for (IceConf conf : confList) {
                appSet.add(conf.getApp());
                confActiveMap.computeIfAbsent(conf.getApp(), k -> new HashMap<>()).put(conf.getId(), conf);
                if (Constant.isLeaf(conf.getType())) {
                    Map<Byte, Map<String, Integer>> map = leafClassMap.get(conf.getApp());
                    Map<String, Integer> classMap;
                    if (map == null) {
                        map = new HashMap<>();
                        leafClassMap.put(conf.getApp(), map);
                        classMap = new HashMap<>();
                        map.put(conf.getType(), classMap);
                        classMap.put(conf.getConfName(), 0);
                    } else {
                        classMap = map.get(conf.getType());
                        if (classMap == null) {
                            classMap = new HashMap<>();
                            map.put(conf.getType(), classMap);
                            classMap.put(conf.getConfName(), 0);
                        } else {
                            classMap.putIfAbsent(conf.getConfName(), 0);
                        }
                    }
                    classMap.put(conf.getConfName(), classMap.get(conf.getConfName()) + 1);
                }
            }
        }
        lastConfUpdateTime = now;
        lastBaseUpdateTime = now;
    }

    /*
     * 根据app获取生效中的ConfList
     */
    public Collection<IceConfDto> getActiveConfsByApp(Integer app) {
        Map<Long, IceConf> map = confActiveMap.get(app);
        if (map == null) {
            return Collections.emptyList();
        }
        return Constant.confListToDtoList(map.values());
    }

    /*
     * 根据app获取生效中的baseList
     */
    public Collection<IceBaseDto> getActiveBasesByApp(Integer app) {
        Map<Long, IceBase> map = baseActiveMap.get(app);
        if (map == null) {
            return Collections.emptyList();
        }
        return Constant.baseListToDtoList(map.values());
    }

    /*
     * 根据app获取初始化json
     */
    @Override
    public String getInitJson(Integer app) {
        synchronized (LOCK) {
            IceTransferDto transferDto = new IceTransferDto();
            transferDto.setInsertOrUpdateBases(this.getActiveBasesByApp(app));
            transferDto.setInsertOrUpdateConfs(this.getActiveConfsByApp(app));
            transferDto.setVersion(version);
            return JSON.toJSONString(transferDto);
        }
    }
}