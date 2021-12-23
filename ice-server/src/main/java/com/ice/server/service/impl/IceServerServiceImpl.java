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
import org.springframework.util.StringUtils;

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

    private final Map<Long, Map<Long, Integer>> prevMapMap = new HashMap<>();
    private final Map<Long, Map<Long, Integer>> nextMapMap = new HashMap<>();
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

    public synchronized boolean haveCircle(Long nodeId, Long linkId) {
        if (nodeId.equals(linkId)) {
            return true;
        }
        Map<Long, Integer> nodeNextMap = nextMapMap.get(nodeId);
        if (!CollectionUtils.isEmpty(nodeNextMap) && nodeNextMap.containsKey(linkId)) {
            return false;
        }
        Map<Long, Integer> linkNextMap = nextMapMap.get(linkId);
        if (CollectionUtils.isEmpty(linkNextMap)) {
            return false;
        }
        Map<Long, Integer> nodePrevMap = prevMapMap.get(nodeId);
        if (CollectionUtils.isEmpty(nodePrevMap)) {
            return false;
        }
        Set<Long> linkNext = linkNextMap.keySet();
        Set<Long> nodePrev = nodePrevMap.keySet();
        return linkNext.contains(nodeId) || nodePrev.contains(linkId);
    }

    @Override
    public synchronized boolean haveCircle(Long nodeId, List<Long> linkIds) {
        if (!CollectionUtils.isEmpty(linkIds)) {
            for (Long linkId : linkIds) {
                boolean res = haveCircle(nodeId, linkId);
                if (res) {
                    return true;
                }
            }
        }
        return false;
    }

    /*
     * link next`s prev add count of node prev
     * node prev`s next add count of link next
     */
    public synchronized void link(Long nodeId, Long linkId) {
        Map<Long, Integer> linkPrevMap = prevMapMap.computeIfAbsent(linkId, k -> new HashMap<>());
        Map<Long, Integer> nodePrevMap = prevMapMap.computeIfAbsent(nodeId, k -> new HashMap<>());
        Map<Long, Integer> linkNextMap = nextMapMap.computeIfAbsent(linkId, k -> new HashMap<>());
        Map<Long, Integer> nodeNextMap = nextMapMap.computeIfAbsent(nodeId, k -> new HashMap<>());
        Integer linkPrevCount = linkPrevMap.computeIfAbsent(nodeId, k -> 0);
        linkPrevMap.put(nodeId, linkPrevCount + 1);
        for (Map.Entry<Long, Integer> entry : nodePrevMap.entrySet()) {
            Integer originCount = linkPrevMap.computeIfAbsent(entry.getKey(), k -> 0);
            linkPrevMap.put(entry.getKey(), originCount + entry.getValue());
        }
        for (Long linkNextId : linkNextMap.keySet()) {
            for (Map.Entry<Long, Integer> entry : linkPrevMap.entrySet()) {
                Integer originCount = prevMapMap.get(linkNextId).computeIfAbsent(entry.getKey(), k -> 0);
                prevMapMap.get(linkNextId).put(entry.getKey(), originCount + entry.getValue());
            }
        }
        Integer nodeNextCount = nodeNextMap.computeIfAbsent(linkId, k -> 0);
        nodeNextMap.put(linkId, nodeNextCount + 1);
        for (Map.Entry<Long, Integer> entry : linkNextMap.entrySet()) {
            Integer originCount = nodeNextMap.computeIfAbsent(entry.getKey(), k -> 0);
            nodeNextMap.put(entry.getKey(), originCount + entry.getValue());
        }
        for (Long nodePrevId : nodePrevMap.keySet()) {
            for (Map.Entry<Long, Integer> entry : nodeNextMap.entrySet()) {
                Integer originCount = nextMapMap.get(nodePrevId).computeIfAbsent(entry.getKey(), k -> 0);
                nextMapMap.get(nodePrevId).put(entry.getKey(), originCount + entry.getValue());
            }
        }
    }

    @Override
    public synchronized void link(Long nodeId, List<Long> linkIds) {
        if (!CollectionUtils.isEmpty(linkIds)) {
            for (Long linkId : linkIds) {
                link(nodeId, linkId);
            }
        }
    }

    /*
     * link next`s prev reduce count of node prev(remove when count<=0)
     * node prev`s next reduce count of link next(remove when count<=0)
     */
    public synchronized void unlink(Long nodeId, Long linkId) {
        Map<Long, Integer> linkPrevMap = prevMapMap.computeIfAbsent(linkId, k -> new HashMap<>());
        Map<Long, Integer> nodePrevMap = prevMapMap.computeIfAbsent(nodeId, k -> new HashMap<>());
        Map<Long, Integer> linkNextMap = nextMapMap.computeIfAbsent(linkId, k -> new HashMap<>());
        Map<Long, Integer> nodeNextMap = nextMapMap.computeIfAbsent(nodeId, k -> new HashMap<>());
        for (Long linkNextId : linkNextMap.keySet()) {
            for (Map.Entry<Long, Integer> entry : linkPrevMap.entrySet()) {
                Integer originCount = prevMapMap.get(linkNextId).computeIfAbsent(entry.getKey(), k -> 0);
                if (originCount <= entry.getValue()) {
                    prevMapMap.get(linkNextId).remove(entry.getKey());
                } else {
                    prevMapMap.get(linkNextId).put(entry.getKey(), originCount - entry.getValue());
                }
            }
        }
        int linkPrevCount = linkPrevMap.computeIfAbsent(nodeId, k -> 0);
        if (linkPrevCount <= 1) {
            linkPrevMap.remove(nodeId);
        } else {
            linkPrevMap.put(nodeId, linkPrevCount - 1);
        }
        for (Map.Entry<Long, Integer> entry : nodePrevMap.entrySet()) {
            Integer originCount = linkPrevMap.computeIfAbsent(entry.getKey(), k -> 0);
            if (originCount <= entry.getValue()) {
                linkPrevMap.remove(entry.getKey());
            } else {
                linkPrevMap.put(entry.getKey(), originCount - entry.getValue());
            }
        }

        for (Long nodePrevId : nodePrevMap.keySet()) {
            for (Map.Entry<Long, Integer> entry : nodeNextMap.entrySet()) {
                Integer originCount = nextMapMap.get(nodePrevId).computeIfAbsent(entry.getKey(), k -> 0);
                if (originCount <= entry.getValue()) {
                    nextMapMap.get(nodePrevId).remove(entry.getKey());
                } else {
                    nextMapMap.get(nodePrevId).put(entry.getKey(), originCount - entry.getValue());
                }
            }
        }
        int nodeNextCount = nodeNextMap.computeIfAbsent(linkId, k -> 0);
        if (nodeNextCount <= 1) {
            nodeNextMap.remove(linkId);
        } else {
            nodeNextMap.put(linkId, nodeNextCount - 1);
        }
        for (Map.Entry<Long, Integer> entry : linkNextMap.entrySet()) {
            Integer originCount = nodeNextMap.computeIfAbsent(entry.getKey(), k -> 0);
            if (originCount <= entry.getValue()) {
                nodeNextMap.remove(entry.getKey());
            } else {
                nodeNextMap.put(entry.getKey(), originCount - entry.getValue());
            }
        }
    }

    @Override
    public IceConf getActiveConfById(Integer app, Long confId) {
        Map<Long, IceConf> confMap = confActiveMap.get(app);
        if (!CollectionUtils.isEmpty(confMap)) {
            return confMap.get(confId);
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

    @Override
    public void removeLeafClass(Integer app, Byte type, String clazz) {
        Map<Byte, Map<String, Integer>> map = leafClassMap.get(app);
        if (!CollectionUtils.isEmpty(map)) {
            Map<String, Integer> typeMap = map.get(type);
            if (!CollectionUtils.isEmpty(typeMap)) {
                typeMap.remove(clazz);
            }
        }
    }

    @Override
    public void addLeafClass(Integer app, Byte type, String clazz) {
        Map<Byte, Map<String, Integer>> map = leafClassMap.get(app);
        Map<String, Integer> classMap;
        if (map == null) {
            map = new HashMap<>();
            leafClassMap.put(app, map);
            classMap = new HashMap<>();
            map.put(type, classMap);
            classMap.put(clazz, 0);
        } else {
            classMap = map.get(type);
            if (classMap == null) {
                classMap = new HashMap<>();
                map.put(type, classMap);
                classMap.put(clazz, 0);
            } else {
                classMap.putIfAbsent(clazz, 0);
            }
        }
        classMap.put(clazz, classMap.get(clazz) + 1);
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
                if (Constant.isRelation(conf.getType()) && StringUtils.hasLength(conf.getSonIds())) {
                    String[] sonIdStrs = conf.getSonIds().split(",");
                    for (String sonIdStr : sonIdStrs) {
                        Long sonId = Long.parseLong(sonIdStr);
                        Map<Long, Integer> prevMap = prevMapMap.computeIfAbsent(sonId, k -> new HashMap<>());
                        int prevCount = prevMap.computeIfAbsent(conf.getId(), k -> 0);
                        prevCount += 1;
                        prevMap.put(conf.getId(), prevCount);
                        Map<Long, Integer> nextMap = nextMapMap.computeIfAbsent(conf.getId(), k -> new HashMap<>());
                        int nextCount = nextMap.computeIfAbsent(sonId, k -> 0);
                        nextCount += 1;
                        nextMap.put(sonId, nextCount);
                    }
                }
                if (conf.getForwardId() != null) {
                    Map<Long, Integer> prevMap = prevMapMap.computeIfAbsent(conf.getForwardId(), k -> new HashMap<>());
                    int prevCount = prevMap.computeIfAbsent(conf.getId(), k -> 0);
                    prevCount += 1;
                    prevMap.put(conf.getId(), prevCount);
                    Map<Long, Integer> nextMap = nextMapMap.computeIfAbsent(conf.getId(), k -> new HashMap<>());
                    int nextCount = nextMap.computeIfAbsent(conf.getForwardId(), k -> 0);
                    nextCount += 1;
                    nextMap.put(conf.getForwardId(), nextCount);
                }
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
            Set<Long> prevDoneSet = new HashSet<>(confList.size());
            Set<Long> nextDoneSet = new HashSet<>(confList.size());
            for (IceConf conf : confList) {
                link(conf.getId(), prevMapMap, prevDoneSet);
                link(conf.getId(), nextMapMap, nextDoneSet);
            }
        }
        lastConfUpdateTime = now;
        lastBaseUpdateTime = now;
    }

    private static void link(Long confId, Map<Long, Map<Long, Integer>> mapMap, Set<Long> doneSet) {
        if (doneSet.contains(confId)) {
            return;
        }
        Map<Long, Integer> map = mapMap.get(confId);
        if (!CollectionUtils.isEmpty(map)) {
            Set<Long> tmpSet = new HashSet<>(map.keySet());
            for (Long id : tmpSet) {
                Map<Long, Integer> countMap = mapMap.get(id);
                if (!CollectionUtils.isEmpty(countMap)) {
                    if (!doneSet.contains(id)) {
                        link(id, mapMap, doneSet);
                    }
                    Map<Long, Integer> doneMapCount = mapMap.get(id);
                    if (!CollectionUtils.isEmpty(doneMapCount)) {
                        for (Map.Entry<Long, Integer> entry : doneMapCount.entrySet()) {
                            Integer originCount = map.computeIfAbsent(entry.getKey(), k -> 0);
                            map.put(entry.getKey(), originCount + entry.getValue());
                        }
                    }
                }
            }
        }
        doneSet.add(confId);
    }

    public Collection<IceConfDto> getActiveConfsByApp(Integer app) {
        Map<Long, IceConf> map = confActiveMap.get(app);
        if (map == null) {
            return Collections.emptyList();
        }
        return Constant.confListToDtoList(map.values());
    }

    public Collection<IceBaseDto> getActiveBasesByApp(Integer app) {
        Map<Long, IceBase> map = baseActiveMap.get(app);
        if (map == null) {
            return Collections.emptyList();
        }
        return Constant.baseListToDtoList(map.values());
    }

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
