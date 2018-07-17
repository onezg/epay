package com.epay.xj.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.transaction.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.alibaba.fastjson.JSON;
import com.epay.xj.domain.OverDueIndex;
import com.epay.xj.domain.TradeDetailDO;
import com.epay.xj.properties.InitProperties;
import com.epay.xj.utils.DateUtils;
import com.epay.xj.utils.MathUtil;

@Service
@Transactional
public class TaskServer {

	Logger logger = LoggerFactory.getLogger(getClass());
	@Autowired
	private InitProperties initProperties;

	@PersistenceContext
	private EntityManager entityManager;

	public List<String> getTaskList(String updateTime, String flag) {
		String sql = "select CERT_NO from CP_ODS.P1055_CERT_LIST_PY";
		return entityManager.createNativeQuery(sql).getResultList();
	}

	/**
	 * batchInsert
	 * 
	 * @param list
	 */
	public void batchInsert(List<OverDueIndex> list) {
		int size = list.size();
		for (int i = 0; i < size; i++) {
			OverDueIndex dd = list.get(i);
			entityManager.persist(dd);
			if (i % 10000 == 0 || i == (size - 1)) { // 每1000条数据执行一次，或者最后不足1000条时执行
				entityManager.flush();
				entityManager.clear();
			}
		}
	}

	/**
	 * 获取certNo下的不同月份下的所有
	 * 
	 * @param certNo
	 * @param updateTime
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public Map<Integer, List<TradeDetailDO>> fatherList(String certNo, String updateTime) {
		// List<TradeDetailDO> tradeDetailList = new ArrayList<TradeDetailDO>();
		Map<Integer, List<TradeDetailDO>> tradeMap = new HashMap<Integer, List<TradeDetailDO>>();
		String sql = "select * from CP_ODS.P1055_TRA_TRADE_DETAIL_PARA_PY where IDCARD='" + certNo + "'";
		try {
			List<TradeDetailDO> tradeDetailList = entityManager.createNativeQuery(sql, TradeDetailDO.class)
					.getResultList();
			Collections.sort(tradeDetailList);
			Map<String, Integer> overDueMouth = initProperties.getOverDueMonth();
			for (int month : overDueMouth.values()) {
				tradeMap.put(month, getListByMonth(tradeDetailList, month, updateTime));
			}
		} catch (Exception e) {
			logger.error("执行sql:{},error:{}", sql, e.getMessage());
			e.printStackTrace();
		}
		return tradeMap;
	}

	public void sliceTask(List<String> taskList, String updateTime) throws InterruptedException {
		// 每500条数据开启一条线程
		int threadSize = Integer.valueOf(initProperties.getThreadSize());
		// 总数据条数
		int dataSize = taskList.size();
		// 线程数
		int threadNum = dataSize / threadSize + 1;
		// 定义标记,过滤threadNum为整数
		boolean special = dataSize % threadSize == 0;
		// 创建一个线程池
		int theadPoolSize = Integer.valueOf(initProperties.getThreadPoolSize());
		ExecutorService exec = Executors.newFixedThreadPool(theadPoolSize);
		// 定义一个任务集合
		List<Callable<List<OverDueIndex>>> tasks = new ArrayList<Callable<List<OverDueIndex>>>();
		Callable<List<OverDueIndex>> task = null;
		List<String> cutList = null;
		logger.info("taskSize:{},线程数：{},单个线程处理记录数量:{}", taskList.size(), theadPoolSize, threadSize);
		// 确定每条线程的数据
		for (int i = 0; i < threadNum; i++) {
			if (i == threadNum - 1) {
				if (special) {
					break;
				}
				cutList = taskList.subList(threadSize * i, dataSize);
			} else {
				cutList = taskList.subList(threadSize * i, threadSize * (i + 1));
			}
			final List<String> listStr = cutList;
			final String udpateTimes = updateTime;
			final Map<String, String[]> returnCodeDic = initProperties.getReturnCodeDic();
			final Map<String, Integer> overDueMouth = initProperties.getOverDueMonth();
			task = new Callable<List<OverDueIndex>>() {
				@Override
				public List<OverDueIndex> call() throws Exception {
					List<OverDueIndex> lst = new ArrayList<OverDueIndex>();
					for (int i = 0; i < listStr.size(); i++) {
						String certNo = listStr.get(i);
						OverDueIndex odi = new OverDueIndex();
						odi.setCERT_NO(certNo);
						// 如果是人的所有记录
						Map<Integer, List<TradeDetailDO>> tradeMap = fatherList(certNo, udpateTimes);
						for (int month : overDueMouth.values()) {
							// 指标结果集
							List<TradeDetailDO> list = tradeMap.get(month);
							overDueMouth(list, odi, month, returnCodeDic);
						}
						// logger.info("odi:{}", JSON.toJSONString(odi));
						lst.add(odi);
					}
					return lst;
				}
			};
			// 这里提交的任务容器列表和返回的Future列表存在顺序对应的关系
			tasks.add(task);
		}

		List<Future<List<OverDueIndex>>> results = exec.invokeAll(tasks);
		// StringBuffer sb = new StringBuffer();
		long sysBeginTime = System.nanoTime();
		for (Future<List<OverDueIndex>> future : results) {
			try {
				// 遍历所有人list

				List<OverDueIndex> lst = future.get();
				logger.info("-------------------------------lst.size:{}", lst.size());
				// sb.append("size:").append(lst.size()).append(",");
				// batchInsert(lst);
				// for (OverDueIndex overDueIndex : lst) {
				// logger.info("certNo:{},index:{}",
				// overDueIndex.getCertNo(),JSON.toJSONString(overDueIndex));
				// }
			} catch (ExecutionException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		String useTime = String.valueOf((System.nanoTime() - sysBeginTime) / Math.pow(10, 9));
		logger.info("sb:{},写入记录useTime:{}秒", null, useTime);
		// 关闭线程池
		exec.shutdown();
	}

	public List<TradeDetailDO> getListByMonth(List<TradeDetailDO> fatherList, int month, String udpateTimes) {
		List<TradeDetailDO> list = new ArrayList<TradeDetailDO>();
		Timestamp end = new Timestamp(DateUtils.yyyyMMddToDate(udpateTimes).getTime());
		Timestamp begin = DateUtils.getDateOfXMonthsAgo(end, month);
		for (TradeDetailDO o : fatherList) {
			if (DateUtils.judge(begin, end, o.getCREATE_TIME())) {
				list.add(o);
			}
		}
		return list;
	}

	public void deal1(String updateTime, String flag) {
		try {
			List<String> taskList = getTaskList(updateTime, flag);
			sliceTask(taskList, updateTime);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public void onePerson(String certNo, String updateTime) {
		Map<String, String[]> returnCodeDic = initProperties.getReturnCodeDic();
		Map<String, Integer> overDueMouth = initProperties.getOverDueMonth();
		OverDueIndex odi = new OverDueIndex();
		odi.setCERT_NO(certNo);
		// 如果是人的所有记录
		Map<Integer, List<TradeDetailDO>> tradeMap = fatherList(certNo, updateTime);
		for (int month : overDueMouth.values()) {
			// 指标结果集
			List<TradeDetailDO> list = tradeMap.get(month);
			overDueMouth(list, odi, month, returnCodeDic);
		}
		logger.info("odi:{}", JSON.toJSONString(odi));
	}

	private void overDueMouth(List<TradeDetailDO> list, OverDueIndex odi, int month,
			Map<String, String[]> returnCodeDic) {

		if (month == 3) {
			/******************************* 逾期一天以上次数 ***************************************/
			odi.setYQ013(loanOrgOverDueOneDay(list, "dk", returnCodeDic));
			odi.setYQ014(loanOrgOverDueOneDay(list, "xj", returnCodeDic));
			odi.setYQ015(loanOrgOverDueOneDay(list, "yh", returnCodeDic));
			odi.setYQ016(loanOrgOverDueOneDay(list, "xd", returnCodeDic));
			/******************************* 逾期机构数 ***************************************/
			odi.setYQ017(overDueOrgCount(list, "dk", returnCodeDic));
			odi.setYQ018(overDueOrgCount(list, "xj", returnCodeDic));
			odi.setYQ019(overDueOrgCount(list, "yh", returnCodeDic));
			odi.setYQ020(overDueOrgCount(list, "xd", returnCodeDic));
			/******************************* 逾期天数总和 ***************************************/
			odi.setYQ027(overDueDaysSum(list, "dk", returnCodeDic));
			/******************************* 逾期金额总和 ***************************************/
			odi.setYQ033(overDueTotalMoneySum(list, returnCodeDic, "1d"));
			odi.setYQ032(overDueTotalMoneySum(list, returnCodeDic, "7d"));
			odi.setYQ031(overDueTotalMoneySum(list, returnCodeDic, "30d"));

			/******************************* 平均逾期次数 ***************************************/
			int dkOverDueOrgAmount = overDueOrgCount(list, "dk", returnCodeDic);
			int xjOverDueOrgAmount = overDueOrgCount(list, "xj", returnCodeDic);
			int yhOverDueOrgAmount = overDueOrgCount(list, "yh", returnCodeDic);
			int xdOverDueOrgAmount = overDueOrgCount(list, "xd", returnCodeDic);

			odi.setYQ022(MathUtil.divide(avgOrgOverDueCount(list, "dk", returnCodeDic), dkOverDueOrgAmount));
			odi.setYQ023(MathUtil.divide(avgOrgOverDueCount(list, "xj", returnCodeDic), xjOverDueOrgAmount));
			odi.setYQ024(MathUtil.divide(avgOrgOverDueCount(list, "yh", returnCodeDic), yhOverDueOrgAmount));
			odi.setYQ025(MathUtil.divide(avgOrgOverDueCount(list, "xd", returnCodeDic), xdOverDueOrgAmount));
			
			/******************************* 授信类变量 ***************************************/
			odi.setSX009(totalCreditLine(list, "dk", returnCodeDic));
			odi.setSX010(totalCreditLine(list, "yh", returnCodeDic));
			odi.setSX011(maxCreditLine(list, "xd", returnCodeDic));
			odi.setSX012(maxCreditLine(list, "xj", returnCodeDic));
			
		     /******************************* 风险类变量 *****************************/
            int dkAcctfAmount = acctfCount(list, "dk", returnCodeDic);
            int xjAcctfAmount = acctfCount(list, "xj", returnCodeDic);
            int yhAcctfAmount = acctfCount(list, "yh", returnCodeDic);
            int xdAcctfAmount = acctfCount(list, "xd", returnCodeDic);
            
            odi.setFX017(dkAcctfAmount);
            odi.setFX018(xjAcctfAmount);
            odi.setFX019(yhAcctfAmount);
            odi.setFX020(xdAcctfAmount);
            odi.setFX021(dkAcctfAmount + xjAcctfAmount + yhAcctfAmount + xdAcctfAmount);
            
            odi.setFX022(acctfProportion(list, returnCodeDic));
            odi.setFX023(acctfMoneyProportion(list, returnCodeDic));
            odi.setFX024(otlmtMoneyProportion(list, returnCodeDic));
            odi.setFX025(otlmtProportion(list, returnCodeDic));
			
		} else if (month == 6) {
			/******************************* 逾期一天以上次数 ***************************************/
			odi.setYQ001(loanOrgOverDueOneDay(list, "dk", returnCodeDic));
			odi.setYQ002(loanOrgOverDueOneDay(list, "xj", returnCodeDic));
			odi.setYQ003(loanOrgOverDueOneDay(list, "yh", returnCodeDic));
			odi.setYQ004(loanOrgOverDueOneDay(list, "xd", returnCodeDic));
			/******************************* 逾期机构数 ***************************************/
			odi.setYQ009(overDueOrgCount(list, "dk", returnCodeDic));
			odi.setYQ010(overDueOrgCount(list, "xj", returnCodeDic));
			odi.setYQ011(overDueOrgCount(list, "yh", returnCodeDic));

			/******************************* 逾期天数总和 ***************************************/

			odi.setYQ026(overDueDaysSum(list, "dk", returnCodeDic));

			/******************************* 逾期金额总和 ***************************************/
			odi.setYQ030(overDueTotalMoneySum(list, returnCodeDic, "1d"));
			odi.setYQ029(overDueTotalMoneySum(list, returnCodeDic, "7d"));
			odi.setYQ028(overDueTotalMoneySum(list, returnCodeDic, "30d"));

			/******************************* 平均逾期次数 ***************************************/
			int dkOverDueOrgAmount = overDueOrgCount(list, "dk", returnCodeDic);
			int xjOverDueOrgAmount = overDueOrgCount(list, "xj", returnCodeDic);
			int yhOverDueOrgAmount = overDueOrgCount(list, "yh", returnCodeDic);
			int xdOverDueOrgAmount = overDueOrgCount(list, "xd", returnCodeDic);

			odi.setYQ005(MathUtil.divide(avgOrgOverDueCount(list, "dk", returnCodeDic), dkOverDueOrgAmount));
			odi.setYQ006(MathUtil.divide(avgOrgOverDueCount(list, "xj", returnCodeDic), xjOverDueOrgAmount));
			odi.setYQ007(MathUtil.divide(avgOrgOverDueCount(list, "yh", returnCodeDic), yhOverDueOrgAmount));
			odi.setYQ008(MathUtil.divide(avgOrgOverDueCount(list, "xd", returnCodeDic), xdOverDueOrgAmount));
			
			/******************************* 放款类变量 ***************************************/
			odi.setFK008(loanOrgLoanSuccessTimes(list, "dk", returnCodeDic));//成功放款的记录数
			odi.setFK009(loanOrgLoanSuccessOrg(list, "dk", returnCodeDic));//成功放款的不同机构数
			odi.setFK010(loanOrgLoanSumMoney(list, "yh", returnCodeDic));//在银行类机构放款的总金额
			odi.setFK011(loanOrgLoanSumMoney(list, "xj", returnCodeDic));//在消费金融类机构放款的总金额
			odi.setFK012(loanOrgLoanSumMoney(list, "xd", returnCodeDic));//在小额贷款类机构放款的总金额
			odi.setFK013(loanOrgLoanSumMoney(list, "dk", returnCodeDic));//在贷款类机构放款的总金额
			
			/******************************* 授信类变量 ***************************************/
			odi.setSX005(totalCreditLine(list, "dk", returnCodeDic));
			odi.setSX006(totalCreditLine(list, "yh", returnCodeDic));
			odi.setSX007(maxCreditLine(list, "xd", returnCodeDic));
			odi.setSX004(maxCreditLine(list, "xj", returnCodeDic));
			
			/******************************* 风险类变量 *****************************/
            int dkAcctfAmount = acctfCount(list, "dk", returnCodeDic);
            int xjAcctfAmount = acctfCount(list, "xj", returnCodeDic);
            int yhAcctfAmount = acctfCount(list, "yh", returnCodeDic);
            int xdAcctfAmount = acctfCount(list, "xd", returnCodeDic);
            
            odi.setFX008(dkAcctfAmount);
            odi.setFX009(xjAcctfAmount);
            odi.setFX010(yhAcctfAmount);
            odi.setFX011(xdAcctfAmount);
            odi.setFX012(dkAcctfAmount + xjAcctfAmount + yhAcctfAmount + xdAcctfAmount);
            
            odi.setFX013(acctfProportion(list, returnCodeDic));
            odi.setFX014(acctfMoneyProportion(list, returnCodeDic));
            odi.setFX015(otlmtMoneyProportion(list, returnCodeDic));
            odi.setFX016(otlmtProportion(list, returnCodeDic));
            
            /******************************* 放款类变量 ***************************************/
            odi.setFK008(fkSuccessCount(list, returnCodeDic));
            odi.setFK009(fkSuccessOrgCount(list, returnCodeDic));
            odi.setFK010(fkSuccessMoneyCount(list, "yh", returnCodeDic));
            odi.setFK011(fkSuccessMoneyCount(list, "xj", returnCodeDic));
            odi.setFK012(fkSuccessMoneyCount(list, "xd", returnCodeDic));
            odi.setFK013(fkSuccessMoneyCount(list, "dk", returnCodeDic));

		} else if (month == 12) {
			/******************************* 逾期一天以上次数 ***************************************/
			odi.setYQ038(loanOrgOverDueOneDay(list, "dk", returnCodeDic));
			odi.setYQ039(loanOrgOverDueOneDay(list, "xj", returnCodeDic));
			odi.setYQ040(loanOrgOverDueOneDay(list, "yh", returnCodeDic));
			odi.setYQ041(loanOrgOverDueOneDay(list, "xd", returnCodeDic));
			/******************************* 逾期机构数 ***************************************/
			odi.setYQ034(overDueOrgCount(list, "dk", returnCodeDic));
			odi.setYQ035(overDueOrgCount(list, "yh", returnCodeDic));
			odi.setYQ036(overDueOrgCount(list, "xj", returnCodeDic));
			odi.setYQ037(overDueOrgCount(list, "xd", returnCodeDic));
			/*******************************
			 * 12个月 最大逾期次数
			 ***************************************/
			odi.setYQ042(everyOrgOverDueMaxTimes(list, "dk", returnCodeDic));
			odi.setYQ043(everyOrgOverDueMaxTimes(list, "xj", returnCodeDic));
			odi.setYQ044(everyOrgOverDueMaxTimes(list, "yh", returnCodeDic));
			odi.setYQ045(everyOrgOverDueMaxTimes(list, "xd", returnCodeDic));
			
			/******************************* 放款类变量 ***************************************/
			odi.setFK001(loanOrgLoanSuccessTimes(list, "dk", returnCodeDic));//成功放款的记录数
			odi.setFK002(loanOrgLoanSuccessOrg(list, "dk", returnCodeDic));//成功放款的不同机构数
			odi.setFK003(odi.getFK003());//fk002 和 fk003 是一样的；
			odi.setFK004(loanOrgLoanSumMoney(list, "yh", returnCodeDic));//在银行类机构放款的总金额
			odi.setFK005(loanOrgLoanSumMoney(list, "xj", returnCodeDic));//在消费金融类机构放款的总金额
			odi.setFK006(loanOrgLoanSumMoney(list, "xd", returnCodeDic));//在小额贷款类机构放款的总金额
			odi.setFK007(loanOrgLoanSumMoney(list, "dk", returnCodeDic));//在贷款类机构放款的总金额
			/*	最近一次	*/
			odi.setFK014(loanOrgRecentLoanDate(list, "dk", returnCodeDic, "2"));
			if(odi.getFK014()!=null){
				Date dateBegin = DateUtils.yyyyMMddToDate(odi.getFK014());
				odi.setFK015(DateUtils.getIntervalDayAmount(dateBegin, new Date()));
			}
			/*	最早一次	*/
			odi.setFK016(loanOrgRecentLoanDate(list, "dk", returnCodeDic, "1"));
			if(odi.getFK016()!=null){
				Date dateBegin = DateUtils.yyyyMMddToDate(odi.getFK016());
				odi.setFK015(DateUtils.getIntervalDayAmount(dateBegin, new Date()));
			}
			
			/******************************* 授信类变量 ***************************************/
			odi.setSX001(maxCreditLine(list, "dk", returnCodeDic));
			odi.setSX002(maxCreditLine(list, "yh", returnCodeDic));
			odi.setSX003(maxCreditLine(list, "xd", returnCodeDic));
			odi.setSX004(maxCreditLine(list, "xj", returnCodeDic));
			
			/******************************* 风险类变量 ***************************************/
            int dkAcctfAmount = acctfCount(list, "dk", returnCodeDic);
            int xjAcctfAmount = acctfCount(list, "xj", returnCodeDic);
            int yhAcctfAmount = acctfCount(list, "yh", returnCodeDic);
            int xdAcctfAmount = acctfCount(list, "xd", returnCodeDic);

            odi.setFX001(dkAcctfAmount);
            odi.setFX002(xjAcctfAmount);
            odi.setFX003(yhAcctfAmount);
            odi.setFX004(xdAcctfAmount);
            odi.setFX005(dkAcctfAmount + xjAcctfAmount + yhAcctfAmount + xdAcctfAmount);
            
            odi.setFX006(acctfProportion(list, returnCodeDic));
            odi.setFX007(acctfMoneyProportion(list, returnCodeDic));
		}
	}

	/**
	 * 每个商户的所有记录
	 * 
	 * @param list
	 * @param orgTypeList
	 * @return
	 */
	public Map<String, List<TradeDetailDO>> merTypeMap(List<TradeDetailDO> list, List<String> orgTypeList) {
		Map<String, List<TradeDetailDO>> map = new HashMap<String, List<TradeDetailDO>>();
		List<TradeDetailDO> records = null;
		for (TradeDetailDO o : list) {
			String merId = o.getSOURCE_MERNO();// 银行卡
			// 非指定机构不参与逾期统计
			if (!orgTypeList.contains(o.getMER_TYPE().toString()))
				continue;
			if (!map.containsKey(merId)) {
				records = new ArrayList<TradeDetailDO>();
				records.add(o);
			} else {
				records = map.get(merId);
				records.add(o);
			}
			map.put(merId, records);
		}
		return map;
	}

	/**
	 * 逾期金额总和 : 近x个月逾期x天以上金额总和 逾期类型3 3.在同一家公司划扣因余额不足失败，直至划扣成功为止
	 * 
	 * @param list
	 * @param indexMap
	 * @param orgType
	 * @param returnCodeDic
	 */
	public BigDecimal overDueTotalMoneySum(List<TradeDetailDO> list, Map<String, String[]> returnCodeDic, String days) {

		List<String> ywbzLst = Arrays.asList(returnCodeDic.get("yebz"));
		// 逾期金额总和
		BigDecimal overDueSumMoney = null;
		BigDecimal singleOverDueSumMoney = new BigDecimal("0.00");
		// 逾期日期值
		Timestamp overDueBeginDate = null;
		// 定义一个用户的银行卡在不同机构下拥有的消费记录集合
		Map<String, List<TradeDetailDO>> map = new HashMap<String, List<TradeDetailDO>>();
		List<TradeDetailDO> records = null;

		for (TradeDetailDO o : list) {
			// 商户号
			String merId = o.getSOURCE_MERNO();

			if (!map.containsKey(merId)) {
				records = new ArrayList<TradeDetailDO>();
				records.add(o);
			} else {
				records = map.get(merId);
				records.add(o);
			}
			map.put(merId, records);
		}
		// 排序和计算逾期
		for (Map.Entry<String, List<TradeDetailDO>> entry : map.entrySet()) {
			List<TradeDetailDO> cardNolist = entry.getValue();
			if (cardNolist.size() <= 1)
				continue;// 如果记录小于等于一条就不参与逾期统计

			// 逾期天数值
			for (TradeDetailDO o : cardNolist) {
				// 余额不足,划扣失败
				if (ywbzLst.contains(o.getRETURN_CODE())) {
					if (!StringUtils.isEmpty(overDueBeginDate))
						continue;// 标记第一次划扣失败时间
					// 逾期失败日期
					overDueBeginDate = o.getCREATE_TIME();
					overDueSumMoney = o.getAMOUNT();
					continue;
				} else if ("0000".contains(o.getRETURN_CODE())) {
					if (StringUtils.isEmpty(overDueBeginDate))
						continue;// 标记第一次划扣失败时间
					// 逾期天数
					int overDueBeginDayTemp = DateUtils.getIntervalDayAmount(overDueBeginDate, o.getCREATE_TIME());
					// 逾期days(天)
					if (overDueBeginDayTemp > initProperties.getOverDueDayDic().get(days)) {
						singleOverDueSumMoney = singleOverDueSumMoney.add(overDueSumMoney);
					}
				}

				// 还原标记第一次划扣失败时间
				if (!StringUtils.isEmpty(overDueBeginDate)) {
					overDueBeginDate = null;
				}
			}
		}

		if (overDueSumMoney != null) {
			overDueSumMoney = overDueSumMoney.setScale(2, BigDecimal.ROUND_HALF_UP);
		}
		return overDueSumMoney;
	}

	/**
	 * 逾期天数总和 : 近x个月逾期x天以上天数总和 逾期类型3 3.在同一家公司划扣因余额不足失败，直至划扣成功为止
	 * 
	 * @param list
	 * @param indexMap
	 * @param orgType
	 * @param returnCodeDic
	 */
	public int overDueDaysSum(List<TradeDetailDO> list, String orgType, Map<String, String[]> returnCodeDic) {
		// 余额不足失败返回码
		List<String> ywbzLst = Arrays.asList(returnCodeDic.get("yebz"));
		// 逾期天数值
		int overDueOneDays = 0;
		// 逾期日期值
		Timestamp overDueBeginDate = null;

		// 定义一个用户的银行卡在不同机构下拥有的消费记录集合
		Map<String, List<TradeDetailDO>> map = new HashMap<String, List<TradeDetailDO>>();
		List<TradeDetailDO> records = null;

		for (TradeDetailDO o : list) {
			// 商户号
			String merId = o.getSOURCE_MERNO();
			if (!map.containsKey(merId)) {
				records = new ArrayList<TradeDetailDO>();
				records.add(o);
			} else {
				records = map.get(merId);
				records.add(o);
			}
			map.put(merId, records);
		}

		// 计算逾期
		for (Map.Entry<String, List<TradeDetailDO>> entry : map.entrySet()) {
			List<TradeDetailDO> cardNolist = entry.getValue();
			if (cardNolist.size() <= 1)
				continue;// 如果记录小于等于一条就不参与逾期统计

			for (TradeDetailDO o : cardNolist) {
				// 余额不足,划扣失败
				if (ywbzLst.contains(o.getRETURN_CODE())) {
					if (!StringUtils.isEmpty(overDueBeginDate))
						continue;// 标记第一次划扣失败时间
					// 逾期失败日期
					overDueBeginDate = o.getCREATE_TIME();
					continue;
				} else if ("0000".contains(o.getRETURN_CODE())) {
					if (StringUtils.isEmpty(overDueBeginDate))
						continue;// 标记第一次划扣失败时间
					// 逾期天数
					int overDueBeginDayTemp = DateUtils.differentDaysByMillisecond(overDueBeginDate,
							o.getCREATE_TIME());
					// 逾期一天以上
					if (overDueBeginDayTemp > initProperties.getOverDueDayDic().get("1d")) {
						overDueOneDays = overDueBeginDayTemp + overDueOneDays;
						overDueBeginDate = null;
					}
				}
			}
			// 还原标记第一次划扣失败时间
			if (!StringUtils.isEmpty(overDueBeginDate)) {
				overDueBeginDate = null;
			}
		}
		return overDueOneDays;
	}

	/**
	 * 近12个月在xx机构逾期的最大每家机构逾期次数 逾期类型3 3.在同一家公司划扣因余额不足失败，直至划扣成功为止
	 * 
	 * @param list
	 * @param indexMap
	 * @param orgType
	 * @param returnCodeDic
	 */
	public int everyOrgOverDueMaxTimes(List<TradeDetailDO> list, String orgType, Map<String, String[]> returnCodeDic) {
		Map<String, String[]> merTypeDic = initProperties.getMerTypeDic();// 商户类型归属分类字典
		List<String> orgTypeList = Arrays.asList(merTypeDic.get(orgType));// 具体机构类
		List<String> ywbzLst = Arrays.asList(returnCodeDic.get("yebz"));// 余额不足失败返回码
		// List<String> success =
		// Arrays.asList(returnCodeDic.get("success"));//划扣成功返回码

		// 逾期天数值
		int overDueOneDayTimes = 0;
		// 最大逾期次数值
		int max = 0;
		// 逾期日期值
		Timestamp overDueBeginDate = null;
		// 定义一个用户的银行卡在不同机构下拥有的消费记录集合
		Map<String, List<TradeDetailDO>> map = new HashMap<String, List<TradeDetailDO>>();
		List<TradeDetailDO> records = null;

		for (TradeDetailDO o : list) {
			// 商户号
			String merId = o.getSOURCE_MERNO();
			// 非指定机构不参与逾期统计
			if (!orgTypeList.contains(o.getMER_TYPE().toString()))
				continue;
			if (!map.containsKey(merId)) {
				records = new ArrayList<TradeDetailDO>();
				records.add(o);
			} else {
				records = map.get(merId);
				records.add(o);
			}
			map.put(merId, records);
		}
		// 计算逾期
		for (Map.Entry<String, List<TradeDetailDO>> entry : map.entrySet()) {
			List<TradeDetailDO> cardNolist = entry.getValue();
			if (cardNolist.size() <= 1)
				continue;// 如果记录小于等于一条就不参与逾期统计

			for (TradeDetailDO o : cardNolist) {
				// 余额不足,划扣失败
				if (ywbzLst.contains(o.getRETURN_CODE())) {
					if (!StringUtils.isEmpty(overDueBeginDate))
						continue;// 标记第一次划扣失败时间
					// 逾期失败日期
					overDueBeginDate = o.getCREATE_TIME();
					overDueOneDayTimes = overDueOneDayTimes + 1;
				}

				// 比较当前逾期的次数和最大逾期次数值比较
				if (max <= overDueOneDayTimes) {
					max = overDueOneDayTimes;
				}
				// 还原标记第一次划扣失败时间
				if (!StringUtils.isEmpty(overDueBeginDate)) {
					overDueBeginDate = null;
				}
			}
		}
		return max;
	}

	/**
	 * 在贷款类机构逾期1天以上次数 逾期类型3 3.在同一家公司划扣因余额不足失败，直至划扣成功为止
	 * 
	 * @param list
	 * @param indexMap
	 * @param orgType
	 * @param returnCodeDic
	 */
	public int loanOrgOverDueOneDay(List<TradeDetailDO> list, String orgType, Map<String, String[]> returnCodeDic) {
		Map<String, String[]> merTypeDic = initProperties.getMerTypeDic();// 商户类型归属分类字典
		List<String> orgTypeList = Arrays.asList(merTypeDic.get(orgType));// 具体机构类
		List<String> ywbzLst = Arrays.asList(returnCodeDic.get("yebz"));// 余额不足失败返回码

		// 逾期天数值
		int overDueOneDayTimes = 0;
		// 逾期日期值
		Timestamp overDueBeginDate = null;
		// 定义一个用户的银行卡在不同机构下拥有的消费记录集合
		Map<String, List<TradeDetailDO>> map = merTypeMap(list, orgTypeList);
		// logger.info("mouth:{},orgType:{},map:{}",
		// 0,orgType,JSON.toJSONString(map));
		// 计算逾期
		for (Map.Entry<String, List<TradeDetailDO>> entry : map.entrySet()) {
			List<TradeDetailDO> cardNolist = entry.getValue();
			if (cardNolist.size() <= 1)
				continue;// 如果记录小于等于一条就不参与逾期统计

			for (TradeDetailDO o : cardNolist) {
				// 余额不足,划扣失败
				if (ywbzLst.contains(o.getRETURN_CODE())) {
					if (!StringUtils.isEmpty(overDueBeginDate)) // 非空跳过找成功
						continue;// 标记第一次划扣失败时间
					// 逾期失败日期
					overDueBeginDate = o.getCREATE_TIME();
					continue;
				} else if ("0000".contains(o.getRETURN_CODE())) {
					if (StringUtils.isEmpty(overDueBeginDate))
						continue;// 标记第一次划扣失败时间
					// 逾期天数
					int overDueBeginDayTemp = DateUtils.getIntervalDayAmount(overDueBeginDate, o.getCREATE_TIME());
					// 逾期一天以上
					if (overDueBeginDayTemp > initProperties.getOverDueDayDic().get("1d")) {
						overDueOneDayTimes = overDueOneDayTimes + 1;
						overDueBeginDate = null;
					}
				}
				// //还原标记第一次划扣失败时间
				// if(!StringUtils.isEmpty(overDueBeginDate)){
				// overDueBeginDate = null;
				// }
			}
		}
		return overDueOneDayTimes;
	}

	/**
	 * 逾期机构数 逾期类型3 3.在同一家公司划扣因余额不足失败，可视为一次在该机构下的逾期
	 * 
	 * @param list
	 * @param indexMap
	 * @param orgType
	 * @param returnCodeDic
	 */
	public int overDueOrgCount(List<TradeDetailDO> list, String orgType, Map<String, String[]> returnCodeDic) {
		Map<String, String[]> merTypeDic = initProperties.getMerTypeDic();// 商户类型归属分类字典
		List<String> orgTypeList = Arrays.asList(merTypeDic.get(orgType));// 具体机构类
		List<String> ywbzLst = Arrays.asList(returnCodeDic.get("yebz"));// 余额不足失败返回码

		// 逾期天数值
		int overDueOneOrgCount = 0;
		// 定义一个用户的银行卡在不同机构下拥有的消费记录集合
		Map<String, List<TradeDetailDO>> map = new HashMap<String, List<TradeDetailDO>>();
		List<TradeDetailDO> records = null;

		for (TradeDetailDO o : list) {
			// 商户号
			String merId = o.getSOURCE_MERNO();
			// 非指定机构不参与逾期统计
			if (!orgTypeList.contains(o.getMER_TYPE().toString()))
				continue;
			if (!map.containsKey(merId)) {
				records = new ArrayList<TradeDetailDO>();
				records.add(o);
			} else {
				records = map.get(merId);
				records.add(o);
			}
			map.put(merId, records);
		}

		// 计算逾期
		for (Map.Entry<String, List<TradeDetailDO>> entry : map.entrySet()) {
			List<TradeDetailDO> cardNolist = entry.getValue();
			for (TradeDetailDO o : cardNolist) {
				// 余额不足,划扣失败,看做该机构下有逾期，逾期机构数加1
				if (ywbzLst.contains(o.getRETURN_CODE())) {
					overDueOneOrgCount++;
					// 跳出当前循环，继续往下寻找
					break;
				}
			}
		}
		return overDueOneOrgCount;
	}

	/**
	 * 在消费金融机构逾期1天以上次数
	 * 
	 * @param list
	 * @param indexMap
	 * @param orgType
	 * @param returnCodeDic
	 */
	public void consumerFinanceOrgOverDueOneDay(List<TradeDetailDO> list, Map<String, Integer> indexMap, String orgType,
			Map<String, String[]> returnCodeDic) {
		Map<String, String[]> merTypeDic = initProperties.getMerTypeDic();
		List<String> orgTypeList = Arrays.asList(merTypeDic.get(orgType));// 机构类型
		String extKey = "_" + orgType;
		// int days = initProperties.getOverDueDayDic().get("1d");
		List<String> ywbzLst = Arrays.asList(returnCodeDic.get("yebz"));
		List<String> success = Arrays.asList(returnCodeDic.get("success"));
		// 定义一个用户在不同机构下拥有的消费记录集合
		Map<String, List<TradeDetailDO>> map = merTypeMap(list, orgTypeList);
		// key orgKey,value:逾期次数（不同机构的逾期次数）
		Map<String, Integer> averageOrgOverDue = new HashMap<String, Integer>();
		// key orgKey,value:逾期天数（不同机构的逾期天数）
		Map<String, Integer> averageOrgOverDay = new HashMap<String, Integer>();
		// 排序和计算逾期
		for (Map.Entry<String, List<TradeDetailDO>> entry : map.entrySet()) {
			String cardNo = entry.getKey();
			List<TradeDetailDO> cardNolist = entry.getValue();
			if (cardNolist.size() <= 1)
				continue;
			// 对集合按照日期进行排序
			// Collections.sort(cardNolist);
			double amout = 0;
			// 逾期日期值
			Timestamp overDueBeginDate = null;
			// 逾期天数值
			int overDueBeginDay = 0;
			for (TradeDetailDO o : cardNolist) {
				if (cardNolist.size() <= 1)
					continue;
				// 余额不足,划扣失败
				if (ywbzLst.contains(o.getRETURN_CODE())) {
					// 记录失败金额
					amout = o.getAMOUNT().doubleValue();
					// 逾期失败日期
					overDueBeginDate = o.getCREATE_TIME();
					continue;
				} else if (success.contains(o.getRETURN_CODE())) {
					String merId = o.getSOURCE_MERNO() + extKey;// 机构平均值主键
					if (!averageOrgOverDue.containsKey(merId)) {
						int i = averageOrgOverDue.get(merId);
						// 逾期次数
						averageOrgOverDue.put(merId, ++i);
					} else {
						averageOrgOverDue.put(merId, 1);
					}
					// 划扣成功,最终划扣成功且划扣成功金额=失败金额
					if (amout == o.getAMOUNT().doubleValue()) {
						// 计算逾期天数
						int overDueBeginDayTemp = DateUtils.differentDaysByMillisecond(overDueBeginDate,
								o.getCREATE_TIME());
						averageOrgOverDay.put(merId, overDueBeginDayTemp + overDueBeginDay);
					}
				}
			}
		}
	}

	/**
	 * 在银行类机构逾期1天以上次数
	 * 
	 * @param list
	 * @param indexMap
	 * @param orgType
	 * @param returnCodeDic
	 */
	public void bankOrgOverDueOneDay(List<TradeDetailDO> list, Map<String, Integer> indexMap, String orgType,
			Map<String, String[]> returnCodeDic) {
		Map<String, String[]> merTypeDic = initProperties.getMerTypeDic();
		List<String> orgTypeList = Arrays.asList(merTypeDic.get(orgType));// 机构类型
		String extKey = "_" + orgType;
		// int days = initProperties.getOverDueDayDic().get("1d");
		List<String> ywbzLst = Arrays.asList(returnCodeDic.get("yebz"));
		List<String> success = Arrays.asList(returnCodeDic.get("success"));
		// 定义一个用户在不同机构下拥有的消费记录集合
		Map<String, List<TradeDetailDO>> map = merTypeMap(list, orgTypeList);
		// key orgKey,value:逾期次数（不同机构的逾期次数）
		Map<String, Integer> averageOrgOverDue = new HashMap<String, Integer>();
		// key orgKey,value:逾期天数（不同机构的逾期天数）
		Map<String, Integer> averageOrgOverDay = new HashMap<String, Integer>();
		// 排序和计算逾期
		for (Map.Entry<String, List<TradeDetailDO>> entry : map.entrySet()) {
			String cardNo = entry.getKey();
			List<TradeDetailDO> cardNolist = entry.getValue();
			if (cardNolist.size() <= 1)
				continue;
			// 对集合按照日期进行排序
			// Collections.sort(cardNolist);
			double amout = 0;
			// 逾期日期值
			Timestamp overDueBeginDate = null;
			// 逾期天数值
			int overDueBeginDay = 0;
			for (TradeDetailDO o : cardNolist) {
				if (cardNolist.size() <= 1)
					continue;
				// 余额不足,划扣失败
				if (ywbzLst.contains(o.getRETURN_CODE())) {
					// 记录失败金额
					amout = o.getAMOUNT().doubleValue();
					// 逾期失败日期
					overDueBeginDate = o.getCREATE_TIME();
					continue;
				} else if (success.contains(o.getRETURN_CODE())) {
					String merId = o.getSOURCE_MERNO() + extKey;// 机构平均值主键
					if (!averageOrgOverDue.containsKey(merId)) {
						int i = averageOrgOverDue.get(merId);
						// 逾期次数
						averageOrgOverDue.put(merId, ++i);
					} else {
						averageOrgOverDue.put(merId, 1);
					}
					// 划扣成功,最终划扣成功且划扣成功金额=失败金额
					if (amout == o.getAMOUNT().doubleValue()) {
						// 计算逾期天数
						int overDueBeginDayTemp = DateUtils.differentDaysByMillisecond(overDueBeginDate,
								o.getCREATE_TIME());
						averageOrgOverDay.put(merId, overDueBeginDayTemp + overDueBeginDay);
					}
				}
			}
		}
	}

	/**
	 * 在小贷款类机构逾期1天以上次数
	 * 
	 * @param list
	 * @param indexMap
	 * @param orgType
	 * @param returnCodeDic
	 */
	public void smallLoanOrgOverDueOneDay(List<TradeDetailDO> list, Map<String, Integer> indexMap, String orgType,
			Map<String, String[]> returnCodeDic) {
		Map<String, String[]> merTypeDic = initProperties.getMerTypeDic();
		List<String> orgTypeList = Arrays.asList(merTypeDic.get(orgType));// 机构类型
		String extKey = "_" + orgType;
		// int days = initProperties.getOverDueDayDic().get("1d");
		List<String> ywbzLst = Arrays.asList(returnCodeDic.get("yebz"));
		List<String> success = Arrays.asList(returnCodeDic.get("success"));
		// 定义一个用户在不同机构下拥有的消费记录集合
		Map<String, List<TradeDetailDO>> map = merTypeMap(list, orgTypeList);
		// key orgKey,value:逾期次数（不同机构的逾期次数）
		Map<String, Integer> averageOrgOverDue = new HashMap<String, Integer>();
		// key orgKey,value:逾期天数（不同机构的逾期天数）
		Map<String, Integer> averageOrgOverDay = new HashMap<String, Integer>();
		// 排序和计算逾期
		for (Map.Entry<String, List<TradeDetailDO>> entry : map.entrySet()) {
			String cardNo = entry.getKey();
			List<TradeDetailDO> cardNolist = entry.getValue();
			if (cardNolist.size() <= 1)
				continue;
			// 对集合按照日期进行排序
			// Collections.sort(cardNolist);
			double amout = 0;
			// 逾期日期值
			Timestamp overDueBeginDate = null;
			// 逾期天数值
			int overDueBeginDay = 0;
			for (TradeDetailDO o : cardNolist) {
				if (cardNolist.size() <= 1)
					continue;
				// 余额不足,划扣失败
				if (ywbzLst.contains(o.getRETURN_CODE())) {
					// 记录失败金额
					amout = o.getAMOUNT().doubleValue();
					// 逾期失败日期
					overDueBeginDate = o.getCREATE_TIME();
					continue;
				} else if (success.contains(o.getRETURN_CODE())) {
					String merId = o.getSOURCE_MERNO() + extKey;// 机构平均值主键
					if (!averageOrgOverDue.containsKey(merId)) {
						int i = averageOrgOverDue.get(merId);
						// 逾期次数
						averageOrgOverDue.put(merId, ++i);
					} else {
						averageOrgOverDue.put(merId, 1);
					}
					// 划扣成功,最终划扣成功且划扣成功金额=失败金额
					if (amout == o.getAMOUNT().doubleValue()) {
						// 计算逾期天数
						int overDueBeginDayTemp = DateUtils.differentDaysByMillisecond(overDueBeginDate,
								o.getCREATE_TIME());
						averageOrgOverDay.put(merId, overDueBeginDayTemp + overDueBeginDay);
					}
				}
			}
		}
	}

	/**
	 * 逾期一天以上计算
	 * 
	 * @param list
	 * @param indexMap
	 * @param orgType
	 * @param returnCodeDic
	 */
	public void calculateOverDue(List<TradeDetailDO> list, Map<String, Integer> indexMap, String orgType,
			Map<String, String[]> returnCodeDic) {
		Map<String, String[]> merTypeDic = initProperties.getMerTypeDic();
		List<String> orgTypeList = Arrays.asList(merTypeDic.get(orgType));// 机构类型
		String extKey = "_" + orgType;
		// int days = initProperties.getOverDueDayDic().get("1d");
		List<String> ywbzLst = Arrays.asList(returnCodeDic.get("yebz"));
		List<String> success = Arrays.asList(returnCodeDic.get("success"));
		// 定义一个用户在不同机构下拥有的消费记录集合
		Map<String, List<TradeDetailDO>> map = merTypeMap(list, orgTypeList);
		// key orgKey,value:逾期次数（不同机构的逾期次数）
		Map<String, Integer> averageOrgOverDue = new HashMap<String, Integer>();
		// key orgKey,value:逾期天数（不同机构的逾期天数）
		Map<String, Integer> averageOrgOverDay = new HashMap<String, Integer>();
		// 排序和计算逾期
		for (Map.Entry<String, List<TradeDetailDO>> entry : map.entrySet()) {
			String cardNo = entry.getKey();
			List<TradeDetailDO> cardNolist = entry.getValue();
			if (cardNolist.size() <= 1)
				continue;
			// 对集合按照日期进行排序
			// Collections.sort(cardNolist);
			double amout = 0;
			// 逾期日期值
			Timestamp overDueBeginDate = null;
			// 逾期天数值
			int overDueBeginDay = 0;
			for (TradeDetailDO o : cardNolist) {
				if (cardNolist.size() <= 1)
					continue;
				// 余额不足,划扣失败
				if (ywbzLst.contains(o.getRETURN_CODE())) {
					// 记录失败金额
					amout = o.getAMOUNT().doubleValue();
					// 逾期失败日期
					overDueBeginDate = o.getCREATE_TIME();
					continue;
				} else if (success.contains(o.getRETURN_CODE())) {
					String merId = o.getSOURCE_MERNO() + extKey;// 机构平均值主键
					if (!averageOrgOverDue.containsKey(merId)) {
						int i = averageOrgOverDue.get(merId);
						// 逾期次数
						averageOrgOverDue.put(merId, ++i);
					} else {
						averageOrgOverDue.put(merId, 1);
					}
					// 划扣成功,最终划扣成功且划扣成功金额=失败金额
					if (amout == o.getAMOUNT().doubleValue()) {
						// 计算逾期天数
						int overDueBeginDayTemp = DateUtils.differentDaysByMillisecond(overDueBeginDate,
								o.getCREATE_TIME());
						averageOrgOverDay.put(merId, overDueBeginDayTemp + overDueBeginDay);
					}
				}
			}
		}
	}

	/**
	 * @Description: 计算某类机构的逾期总次数
	 * @param list
	 * @param string
	 * @param returnCodeDic
	 * @return
	 * @author LZG
	 * @date 2018年07月10日
	 */
	private int avgOrgOverDueCount(List<TradeDetailDO> list, String orgType, Map<String, String[]> returnCodeDic) {

		Map<String, String[]> merTypeDic = initProperties.getMerTypeDic();// 商户类型归属分类字典
		List<String> orgTypeList = Arrays.asList(merTypeDic.get(orgType));// 具体机构类
		List<String> ywbzLst = Arrays.asList(returnCodeDic.get("yebz"));// 余额不足失败返回码

		// 某类机构的逾期逾期总次数
		int averageOrgOverDueTime = 0;

		// 定义一个用户的银行卡在不同机构下拥有的消费记录集合
		Map<String, List<TradeDetailDO>> map = merTypeMap(list, orgTypeList);

		// 排序和计算逾期
		for (Map.Entry<String, List<TradeDetailDO>> entry : map.entrySet()) {
			List<TradeDetailDO> cardNolist = entry.getValue();
			for (TradeDetailDO o : cardNolist) {
				if (ywbzLst.contains(o.getRETURN_CODE())) {
					averageOrgOverDueTime++;
				}
			}
		}

		return averageOrgOverDueTime;

	}

	/**
	 * 在贷款类机构x月成功放款次数 放款类变量 3.近x个月成功放款的记录数
	 * 
	 * @param list
	 * @param indexMap
	 * @param orgType:
	 *            dk
	 * @param returnCodeDic
	 */
	public int loanOrgLoanSuccessTimes(List<TradeDetailDO> list, String orgType, Map<String, String[]> returnCodeDic) {
		Map<String, String[]> merTypeDic = initProperties.getMerTypeDic();// 商户类型归属分类字典
		List<String> orgTypeList = Arrays.asList(merTypeDic.get(orgType));// 具体机构类
		List<String> success = Arrays.asList(returnCodeDic.get("success"));
		// 放款的记录数
		int records = 0;
		// 近x个月成功放款的记录数
		for (TradeDetailDO o : list) {
			// 放款成功
			if (orgTypeList.contains(o.getMER_TYPE().toString()) 
					&& o.getSF_TYPE().toString().equals("F") 
					&& success.contains(o.getRETURN_CODE())) {
				records++;
			}
		}
		return records;
	}

	/**
	 * 在贷款类机构放款成功的机构数 放款类变量 3.近x个月在贷款类机构放款成功的机构数
	 * 
	 * @param list
	 * @param indexMap
	 * @param orgType:
	 *            dk
	 * @param returnCodeDic
	 */
	public int loanOrgLoanSuccessOrg(List<TradeDetailDO> list, String orgType, Map<String, String[]> returnCodeDic) {
		Map<String, String[]> merTypeDic = initProperties.getMerTypeDic();// 商户类型归属分类字典
		List<String> orgTypeList = Arrays.asList(merTypeDic.get(orgType));// 具体机构类
		List<String> success = Arrays.asList(returnCodeDic.get("success"));
		// 逾期天数值
		int records = 0;
		// 定义一个用户的银行卡在不同机构下拥有的消费记录集合
		Map<String, List<TradeDetailDO>> map = merTypeMap(list, orgTypeList);

		// 不同结构下成功放款的机构统计
		for (Map.Entry<String, List<TradeDetailDO>> entry : map.entrySet()) {
			List<TradeDetailDO> cardNolist = entry.getValue();
			for (TradeDetailDO o : cardNolist) {
				if (o.getSF_TYPE().toString().equals("F") && success.contains(o.getRETURN_CODE())) {
					records++;
				}
			}
		}
		return records;
	}

	/**
	 * 放款类变量。x个月。总金额。x机构
	 * 
	 * @param list
	 * @param indexMap
	 * @param orgType:
	 *            dk
	 * @param returnCodeDic
	 */
	public BigDecimal loanOrgLoanSumMoney(List<TradeDetailDO> list, String orgType, Map<String, String[]> returnCodeDic) {
		Map<String, String[]> merTypeDic = initProperties.getMerTypeDic();// 商户类型归属分类字典
		List<String> orgTypeList = Arrays.asList(merTypeDic.get(orgType));// 具体机构类
//		List<String> success = Arrays.asList(returnCodeDic.get("success"));
		// 逾期天数值
		BigDecimal records = new BigDecimal(0);
		// 近x个月成功放款的记录数
		for (TradeDetailDO o : list) {
			// 放款成功
			if (orgTypeList.contains(o.getMER_TYPE())) {
				if (o.getSF_TYPE().toString().equals("F")) {
					records.add(o.getAMOUNT());
				}
			}
		}
		if(records.intValue()!=0){
			records.setScale(2, BigDecimal.ROUND_UP);
		}
		return records;
	}
	
	/**
	 * 获取最近放款最近的记录
	 * @param list
	 * @param orgTypeList
	 * @param flg:最早:1 最近:2
	 * @return
	 */
	public TradeDetailDO recentOrFirstLoanRecord(List<TradeDetailDO> list ,List<String> orgTypeList,String flg){
		List<TradeDetailDO> lst = new ArrayList<TradeDetailDO>();
		for (TradeDetailDO o : list) {
			if (orgTypeList.contains(o.getMER_TYPE())) {
				lst.add(o);
			}
		}
		if(lst.size()!=0){
			if(flg.equals("1")){
				return lst.get(0);
			}else if(flg.equals("2")){
				return lst.get(lst.size()-1);
			}
		}
		return null;
	}
	
	
	/**
	 * 放款类变量。最近(最早)一次。时间指标.最近一次在贷款机构放款的日期 flg:最早:1 最近:2
	 * @param list
	 * @param indexMap
	 * @param orgType:
	 * @param returnCodeDic
	 * @param flg:最早:1 最近:2
	 */
	public String loanOrgRecentLoanDate(List<TradeDetailDO> list, String orgType, Map<String, String[]> returnCodeDic,String flg) {
		Map<String, String[]> merTypeDic = initProperties.getMerTypeDic();// 商户类型归属分类字典
		List<String> orgTypeList = Arrays.asList(merTypeDic.get(orgType));// 具体机构类
//		List<String> success = Arrays.asList(returnCodeDic.get("success"));
		// 逾期天数值
		String records = null;
		// 近x个月成功放款的记录数
		TradeDetailDO o = recentOrFirstLoanRecord(list, orgTypeList, flg);
		if(null!=o){
			Date date = new Date();
			date.setTime(o.getCREATE_TIME().getTime());
			records = DateUtils.yyyyMMddToString(date);
		}
		return records;
	}
	
	/**
	 * 授信类变量.最大额度
	 * @param list
	 * @param orgType
	 * @param returnCodeDic
	 * @return
	 */
	public BigDecimal maxCreditLine(List<TradeDetailDO> list, String orgType, Map<String, String[]> returnCodeDic){
		Map<String, String[]> merTypeDic = initProperties.getMerTypeDic();// 商户类型归属分类字典
		List<String> orgTypeList = Arrays.asList(merTypeDic.get(orgType));// 具体机构类
		List<BigDecimal> maxLst = new ArrayList<BigDecimal>();
		for (TradeDetailDO o : list) {
			if(orgTypeList.contains(o.getMER_TYPE().toString()) && o.getSF_TYPE().toString().equals("S")){
				maxLst.add(o.getAMOUNT());
			}
		}
		return Collections.max(maxLst);
	}
	
	/**
	 * 授信类变量.总额度
	 * @param list
	 * @param orgType
	 * @param returnCodeDic
	 * @return
	 */
	public BigDecimal totalCreditLine(List<TradeDetailDO> list, String orgType, Map<String, String[]> returnCodeDic){
		Map<String, String[]> merTypeDic = initProperties.getMerTypeDic();// 商户类型归属分类字典
		List<String> orgTypeList = Arrays.asList(merTypeDic.get(orgType));// 具体机构类
		BigDecimal total = new BigDecimal(0);
		for (TradeDetailDO o : list) {
			if(orgTypeList.contains(o.getMER_TYPE().toString()) && o.getSF_TYPE().toString().equals("S")){
				total.add(o.getAMOUNT());
			}
		}
		if(total.intValue()!=0){
			total.setScale(2, BigDecimal.ROUND_UP);
		}
		return total;
	}
	
	/**
     * @Description: 风险类变量。因账户原因还款失败的次数。
     * @param list
     * @param string
     * @param returnCodeDic
     * @return 
     * @author LZG
     * @date 2018年07月17日
     */
    private int acctfCount(List<TradeDetailDO> list, String orgType, Map<String, String[]> returnCodeDic) {
        
        // 商户类型归属分类字典
        Map<String, String[]> merTypeDic = initProperties.getMerTypeDic();
        // 具体机构类
        List<String> orgTypeList = Arrays.asList(merTypeDic.get(orgType));
        // 帐户问题还款失败的返回码
        List<String> acctfList = Arrays.asList(returnCodeDic.get("acctf"));
        
        List<TradeDetailDO> merTypeTradeDetailDOList = new ArrayList<TradeDetailDO>();
        
        for(TradeDetailDO o : list) {
            if (orgTypeList.contains(o.getMER_TYPE().toString())) {
                merTypeTradeDetailDOList.add(o);
            }
        }
        
        int acctfCountResult = 0;
        for (TradeDetailDO o : merTypeTradeDetailDOList) { 
            if(acctfList.contains(o.getRETURN_CODE())) {
                acctfCountResult++;
            }
        }
        
        return acctfCountResult;
    }
    
    /**
     * @Description: 风险类变量。因账户原因还款失败的记录数占比。
     * @param list
     * @param returnCodeDic
     * @return 
     * @author LZG
     * @date 2018年07月17日
     */
    private BigDecimal acctfProportion(List<TradeDetailDO> list, Map<String, String[]> returnCodeDic) {
        
        // 帐户问题还款失败的返回码
        List<String> acctfList = Arrays.asList(returnCodeDic.get("acctf"));
        
        int oltmtAmount = 0;
        for (TradeDetailDO o : list) { 
            if(acctfList.contains(o.getRETURN_CODE())) {
                oltmtAmount++;
            }
        }
        
        return MathUtil.divide(oltmtAmount, acctfList.size());
    }
    
    /**
     * @Description: 风险类变量。因账户原因还款失败的金额占比。
     * @param list
     * @param returnCodeDic
     * @return 
     * @author LZG
     * @date 2018年07月17日
     */
    private BigDecimal acctfMoneyProportion(List<TradeDetailDO> list, Map<String, String[]> returnCodeDic) {
        
        // 帐户问题还款失败的返回码
        List<String> acctfList = Arrays.asList(returnCodeDic.get("acctf"));
        
        BigDecimal sumMoney = new BigDecimal("0"); 
        BigDecimal acctfMoney = new BigDecimal("0"); 
        
        for (TradeDetailDO o : list) { 
            sumMoney = sumMoney.add(o.getAMOUNT());
            if(acctfList.contains(o.getRETURN_CODE())) {
                acctfMoney = acctfMoney.add(o.getAMOUNT());
            }
        }
        
        return MathUtil.divide(acctfMoney, sumMoney);
    }
    
    /**
     * @Description: 风险类变量。超出限额还款失败的金额占比。
     * @param list
     * @param returnCodeDic
     * @return 
     * @author LZG
     * @date 2018年07月17日
     */
    private BigDecimal otlmtMoneyProportion(List<TradeDetailDO> list, Map<String, String[]> returnCodeDic) {
        
        // 超出限额还款失败的返回码
        List<String> otlmtList = Arrays.asList(returnCodeDic.get("otlmt"));
        
        BigDecimal sumMoney = new BigDecimal("0"); 
        BigDecimal otlmtMoney = new BigDecimal("0"); 
        
        for (TradeDetailDO o : list) { 
            sumMoney = sumMoney.add(o.getAMOUNT());
            if(otlmtList.contains(o.getRETURN_CODE())) {
                otlmtMoney = otlmtMoney.add(o.getAMOUNT());
            }
        }
        
        return MathUtil.divide(otlmtMoney, sumMoney);
    }
    
    /**
     * @Description: 风险类变量。超出限额还款失败的记录数占比。
     * @param list
     * @param returnCodeDic
     * @return 
     * @author LZG
     * @date 2018年07月17日
     */
    private BigDecimal otlmtProportion(List<TradeDetailDO> list, Map<String, String[]> returnCodeDic) {
       
        // 帐户问题还款失败的返回码
        List<String> otlmtList = Arrays.asList(returnCodeDic.get("otlmt"));
        
        int oltmtAmount = 0;
        for (TradeDetailDO o : list) { 
            if(otlmtList.contains(o.getRETURN_CODE())) {
                oltmtAmount++;
            }
        }
        
        return MathUtil.divide(oltmtAmount, otlmtList.size());
    }
    
    /**
     * @Description: 放款类变量。放款成功次数。
     * @param list
     * @param returnCodeDic
     * @return 
     * @author LZG
     * @date 2018年07月17日
     */
    private int fkSuccessCount(List<TradeDetailDO> list, Map<String, String[]> returnCodeDic) {
       
        List<String> successList = Arrays.asList(returnCodeDic.get("success"));
        
        int fkSuccessCountResult = 0;
        for(TradeDetailDO o : list) {
            if('F' == o.getSF_TYPE()) {
                if(successList.contains(o.getRETURN_CODE())) {
                    fkSuccessCountResult++;
                }
            }
        }
        return fkSuccessCountResult;
    }
    
    /**
     * @Description: 放款类变量。放款成功的总机构数。
     * @param list
     * @param returnCodeDic
     * @return 
     * @author LZG
     * @date 2018年07月17日
     */
    private int fkSuccessOrgCount(List<TradeDetailDO> list, Map<String, String[]> returnCodeDic) {
        
        List<String> successList = Arrays.asList(returnCodeDic.get("success"));
        
        Set<String> fkSuccessOrgCountSet = new HashSet<String>();
        for(TradeDetailDO o : list) {
            if('F' == o.getSF_TYPE()) {
                if(successList.contains(o.getRETURN_CODE())) {
                    fkSuccessOrgCountSet.add(o.getSOURCE_MERNO());
                }
            }
        }
        return fkSuccessOrgCountSet.size();
    }
    
    /**
     * @Description: 放款类变量。不同机构放款成功的机构数。
     * @param list
     * @param string
     * @param returnCodeDic
     * @return 
     * @author LZG
     * @date 2018年07月17日
     */
    private int fksuccessOrgCount(List<TradeDetailDO> list, String orgType, Map<String, String[]> returnCodeDic) {
        
        List<String> successList = Arrays.asList(returnCodeDic.get("success"));
        Map<String, String[]> merTypeDic = initProperties.getMerTypeDic();
        List<String> orgTypeList = Arrays.asList(merTypeDic.get(orgType));
        
        //根据机构类别筛选数据
        List<TradeDetailDO> merTypeTradeDetailDOList = new ArrayList<TradeDetailDO>();
        
        for(TradeDetailDO o : list) {
            if (orgTypeList.contains(o.getMER_TYPE().toString())) {
                merTypeTradeDetailDOList.add(o);
            }
        }
        
        Set<String> fkSuccessOrgCountSet = new HashSet<String>();
        for(TradeDetailDO o : merTypeTradeDetailDOList) {
            if('F' == o.getSF_TYPE()) {
                if(successList.contains(o.getRETURN_CODE())) {
                    fkSuccessOrgCountSet.add(o.getSOURCE_MERNO());
                }
            }
        }
        return fkSuccessOrgCountSet.size();
    }
    
    /**
     * @Description: 放款类变量。放款总金额。
     * @param list
     * @param string
     * @param returnCodeDic
     * @return 
     * @author LZG
     * @date 2018年07月17日
     */
    private BigDecimal fkSuccessMoneyCount(List<TradeDetailDO> list, String orgType, Map<String, String[]> returnCodeDic) {
        
        List<String> successList = Arrays.asList(returnCodeDic.get("success"));
        Map<String, String[]> merTypeDic = initProperties.getMerTypeDic();
        List<String> orgTypeList = Arrays.asList(merTypeDic.get(orgType));
        
        //根据机构类别筛选数据
        List<TradeDetailDO> merTypeTradeDetailDOList = new ArrayList<TradeDetailDO>();
        for(TradeDetailDO o : list) {
            if (orgTypeList.contains(o.getMER_TYPE().toString())) {
                merTypeTradeDetailDOList.add(o);
            }
        }
        
        BigDecimal fkSuccessMoney = new BigDecimal("0");
        for(TradeDetailDO o : merTypeTradeDetailDOList) {
            if('F' == o.getSF_TYPE()) {
                if(successList.contains(o.getRETURN_CODE())) {
                    fkSuccessMoney.add(o.getAMOUNT());
                }
            }
        }
        
        return fkSuccessMoney.setScale(2, BigDecimal.ROUND_HALF_UP);
    }
    
    /**
     * @Description: 放款类变量。最近一次。
     * @param list
     * @param returnCodeDic
     * @return Map<String, String> key:fkDate ||fkDays
     * @author LZG
     * @date 2018年07月17日
     */
    private Map<String, Object> fkLastestTimeCount(List<TradeDetailDO> list, Map<String, String[]> returnCodeDic) {
        
        Map<String, Object> resultMap = new HashMap<String, Object>();
        List<String> successList = Arrays.asList(returnCodeDic.get("success"));
        
        int listSize = list.size();
        for(int i = listSize - 1; i >= 0; i--) {
            TradeDetailDO o = list.get(i);
            if('F' == o.getSF_TYPE()) {
                if(successList.contains(o.getRETURN_CODE())) {
                    resultMap.put("fkDate", DateUtils.yyyyMMddToString(o.getCREATE_TIME()));
                    //第2个参数是今天
                    int fkDays = DateUtils.getIntervalDayAmount(o.getCREATE_TIME(), o.getCREATE_TIME());
                    resultMap.put("fkDays", fkDays);
                }
            }
        }
        
        return resultMap;
    }
    
    /**
     * @Description: 放款类变量。最近一次。
     * @param list
     * @param returnCodeDic
     * @return Map<String, String> key:fkDate ||fkDays
     * @author LZG
     * @date 2018年07月17日
     */
    private Map<String, Object> fkEarliestTimeCount(List<TradeDetailDO> list, Map<String, String[]> returnCodeDic) {
        
        Map<String, Object> resultMap = new HashMap<String, Object>();
        List<String> successList = Arrays.asList(returnCodeDic.get("success"));
        
        for(TradeDetailDO o : list) {
            if('F' == o.getSF_TYPE()) {
                if(successList.contains(o.getRETURN_CODE())) {
                    resultMap.put("fkDate", DateUtils.yyyyMMddToString(o.getCREATE_TIME()));
                    //第2个参数是今天
                    int fkDays = DateUtils.getIntervalDayAmount(o.getCREATE_TIME(), o.getCREATE_TIME());
                    resultMap.put("fkDays", fkDays);
                }
            }
        }
        
        return resultMap;
    }
    
}
