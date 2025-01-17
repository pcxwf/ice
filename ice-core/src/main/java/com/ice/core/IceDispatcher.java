package com.ice.core;

import com.alibaba.fastjson.JSON;
import com.ice.common.enums.DebugEnum;
import com.ice.common.exception.NodeException;
import com.ice.core.base.BaseNode;
import com.ice.core.cache.IceConfCache;
import com.ice.core.cache.IceHandlerCache;
import com.ice.core.context.IceContext;
import com.ice.core.context.IcePack;
import com.ice.core.context.IceRoam;
import com.ice.core.handler.IceHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author zjn
 * ice dispatcher with id/scene
 */
@Slf4j
public final class IceDispatcher {

    private IceDispatcher() {
    }

    public static List<IceContext> syncDispatcher(IcePack pack) {
        if (!checkPack(pack)) {
            return Collections.emptyList();
        }
        /*first iceId*/
        if (pack.getIceId() > 0) {
            IceHandler handler = IceHandlerCache.getHandlerById(pack.getIceId());
            if (handler == null) {
                log.debug("handler maybe expired iceId:{}", pack.getIceId());
                return Collections.emptyList();
            }
            IceContext cxt = new IceContext(handler.findIceId(), pack);
            handler.handle(cxt);
            return Collections.singletonList(cxt);
        }
        /*next scene*/
        if (pack.getScene() != null && !pack.getScene().isEmpty()) {
            Map<Long, IceHandler> handlerMap = IceHandlerCache.getHandlersByScene(pack.getScene());
            if (handlerMap == null || handlerMap.isEmpty()) {
                log.debug("handlers maybe all expired scene:{}", pack.getScene());
                return Collections.emptyList();
            }

            List<IceContext> cxtList = new LinkedList<>();
            if (handlerMap.size() == 1) {
                /*one handler*/
                IceHandler handler = handlerMap.values().iterator().next();
                IceContext cxt = new IceContext(handler.findIceId(), pack);
                handler.handle(cxt);
                cxtList.add(cxt);
                return cxtList;
            }
            /*mutli handler ever each handler roam not conflict(note the effect of roam`s shallow copy)*/
            IceRoam roam = pack.getRoam();
            for (IceHandler handler : handlerMap.values()) {
                IceContext cxt = new IceContext(handler.findIceId(), pack.newPack(roam));
                handler.handle(cxt);
                cxtList.add(cxt);
            }
            return cxtList;
        }

        /*last confId/nodeId*/
        long confId = pack.getConfId();
        if (confId <= 0) {
            return Collections.emptyList();
        }
        IceContext cxt = new IceContext(confId, pack);
        if (DebugEnum.filter(DebugEnum.IN_PACK, pack.getDebug())) {
            log.info("handle confId:{} in pack:{}", confId, JSON.toJSONString(pack));
        }
        BaseNode root = IceConfCache.getConfById(confId);
        if (root != null) {
            try {
                root.process(cxt);
                if (DebugEnum.filter(DebugEnum.PROCESS, pack.getDebug())) {
                    log.info("handle confId:{} process:{}", confId, cxt.getProcessInfo().toString());
                }
                if (DebugEnum.filter(DebugEnum.OUT_PACK, pack.getDebug())) {
                    log.info("handle confId:{} out pack:{}", confId, JSON.toJSONString(pack));
                } else {
                    if (DebugEnum.filter(DebugEnum.OUT_ROAM, pack.getDebug())) {
                        log.info("handle confId:{} out roam:{}", confId, JSON.toJSONString(pack.getRoam()));
                    }
                }
            } catch (NodeException ne) {
                log.error("error occur in node confId:{} node:{} cxt:{}", confId, cxt.getCurrentId(), JSON.toJSONString(cxt), ne);
            } catch (Exception e) {
                log.error("error occur confId:{} node:{} cxt:{}", confId, cxt.getCurrentId(), JSON.toJSONString(cxt), e);
            }
        } else {
            log.error("root not exist please check! confId:{}", confId);
        }
        return Collections.singletonList(cxt);
    }

    public static void asyncDispatcher(IcePack pack) {
        if (!checkPack(pack)) {
            return;
        }
        if (pack.getIceId() > 0) {
            IceHandler handler = IceHandlerCache.getHandlerById(pack.getIceId());
            if (handler == null) {
                log.debug("handler maybe expired iceId:{}", pack.getIceId());
                return;
            }
            IceContext cxt = new IceContext(handler.findIceId(), pack);
            handler.handle(cxt);
            return;
        }
        if (pack.getScene() != null && !pack.getScene().isEmpty()) {
            Map<Long, IceHandler> handlerMap = IceHandlerCache.getHandlersByScene(pack.getScene());
            if (handlerMap == null || handlerMap.isEmpty()) {
                log.debug("handlers maybe all expired scene:{}", pack.getScene());
                return;
            }
            if (handlerMap.size() == 1) {
                IceHandler handler = handlerMap.values().iterator().next();
                IceContext cxt = new IceContext(handler.findIceId(), pack);
                handler.handle(cxt);
                return;
            }
            IceRoam roam = pack.getRoam();
            for (IceHandler handler : handlerMap.values()) {
                IceContext cxt = new IceContext(handler.findIceId(), pack.newPack(roam));
                handler.handle(cxt);
            }
        }
        long confId = pack.getConfId();
        if (confId <= 0) {
            return;
        }
        IceContext cxt = new IceContext(confId, pack);
        if (DebugEnum.filter(DebugEnum.IN_PACK, pack.getDebug())) {
            log.info("handle confId:{} in pack:{}", confId, JSON.toJSONString(pack));
        }
        BaseNode root = IceConfCache.getConfById(confId);
        if (root != null) {
            try {
                root.process(cxt);
                if (DebugEnum.filter(DebugEnum.PROCESS, pack.getDebug())) {
                    log.info("handle confId:{} process:{}", confId, cxt.getProcessInfo().toString());
                }
                if (DebugEnum.filter(DebugEnum.OUT_PACK, pack.getDebug())) {
                    log.info("handle confId:{} out pack:{}", confId, JSON.toJSONString(pack));
                } else {
                    if (DebugEnum.filter(DebugEnum.OUT_ROAM, pack.getDebug())) {
                        log.info("handle confId:{} out roam:{}", confId, JSON.toJSONString(pack.getRoam()));
                    }
                }
            } catch (NodeException ne) {
                log.error("error occur in node confId:{} node:{} cxt:{}", confId, cxt.getCurrentId(), JSON.toJSONString(cxt), ne);
            } catch (Exception e) {
                log.error("error occur confId:{} node:{} cxt:{}", confId, cxt.getCurrentId(), JSON.toJSONString(cxt), e);
            }
        } else {
            log.error("root not exist please check! confId:{}", confId);
        }
    }

    private static boolean checkPack(IcePack pack) {
        if (pack == null) {
            log.error("invalid pack null");
            return false;
        }
        if (pack.getIceId() > 0) {
            return true;
        }
        if (pack.getScene() != null && !pack.getScene().isEmpty()) {
            return true;
        }
        if (pack.getConfId() > 0) {
            return true;
        }
        log.error("invalid pack none iceId none scene none confId:{}", JSON.toJSONString(pack));
        return false;
    }
}
