/******************************************************************************
 * Product: Adempiere ERP & CRM Smart Business Solution                       *
 * Copyright (C) 1999-2006 ComPiere, Inc. All Rights Reserved.                *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * You should have received a copy of the GNU General Public License along    *
 * with this program; if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 * For the text or an alternative of this public license, you may reach us    *
 * ComPiere, Inc., 2620 Augustine Dr. #245, Santa Clara, CA 95054, USA        *
 * or via info@compiere.org or http://www.compiere.org/license.html           *
 *****************************************************************************/
package org.compiere.process;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;

import org.adempiere.base.event.EventManager;
import org.adempiere.base.event.EventProperty;
import org.adempiere.base.event.IEventManager;
import org.adempiere.base.event.IEventTopics;
import org.adempiere.util.IProcessUI;
import org.compiere.model.MPInstance;
import org.compiere.model.PO;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Msg;
import org.compiere.util.Trx;
import org.compiere.util.TrxEventListener;
import org.osgi.service.event.Event;

/**
 *  Server Process Template
 *
 *  @author     Jorg Janke
 *  @version    $Id: SvrProcess.java,v 1.4 2006/08/10 01:00:44 jjanke Exp $
 *  
 * @author Teo Sarca, SC ARHIPAC SERVICE SRL
 * 			<li>FR [ 1646891 ] SvrProcess - post process support
 * 			<li>BF [ 1877935 ] SvrProcess.process should catch all throwables
 * 			<li>FR [ 1877937 ] SvrProcess: added commitEx method
 * 			<li>BF [ 1878743 ] SvrProcess.getAD_User_ID
 *			<li>BF [ 1935093 ] SvrProcess.unlock() is setting invalid result
 *			<li>FR [ 2788006 ] SvrProcess: change access to some methods
 *				https://sourceforge.net/tracker/?func=detail&aid=2788006&group_id=176962&atid=879335
 */
@org.adempiere.base.annotation.Process
public abstract class SvrProcess implements ProcessCall
{
	public static final String PROCESS_INFO_CTX_KEY = "ProcessInfo";
	public static final String PROCESS_UI_CTX_KEY = "ProcessUI";
	
	private List<ProcessInfoLog> listEntryLog;  

	/**
	 * Add log to buffer, only process total success, flush buffer
	 * @param id
	 * @param date
	 * @param number
	 * @param msg
	 * @param tableId
	 * @param recordId
	 */
	public void addBufferLog(int id, Timestamp date, BigDecimal number, String msg, int tableId ,int recordId) {
		ProcessInfoLog entryLog = new ProcessInfoLog(id, date, number, msg, tableId, recordId);
		
		if (listEntryLog == null)
			listEntryLog = new ArrayList<ProcessInfoLog>();
		
		listEntryLog.add(entryLog);
	}

	/**
	 *  Server Process.
	 * 	Note that the class is initiated by startProcess.
	 */
	public SvrProcess()
	{
	//	Env.ZERO.divide(Env.ZERO);
	}   //  SvrProcess

	private Properties  		m_ctx;
	private ProcessInfo			m_pi;

	/**	Logger							*/
	protected CLogger			log = CLogger.getCLogger (getClass());

	/**	Is the Object locked			*/
	private boolean				m_locked = false;
	/**	Loacked Object					*/
	private PO					m_lockedObject = null;
	/** Process Main transaction 		*/
	private Trx 				m_trx;
	protected IProcessUI 	processUI;

	/**	Common Error Message			*/
	protected static String 	MSG_SaveErrorRowNotFound = "@SaveErrorRowNotFound@";
	protected static String		MSG_InvalidArguments = "@InvalidArguments@";


	/**
	 *  Start the process.
	 *  Calls the abstract methods <code>process</code>.
	 *  It should only return false, if the function could not be performed
	 *  as this causes the process to abort.
	 *
	 *  @param ctx      Context
	 *  @param pi		Process Info
	 *  @return true if the next process should be performed
	 * 	@see org.compiere.process.ProcessCall#startProcess(Properties, ProcessInfo, Trx)
	 */
	public final boolean startProcess (Properties ctx, ProcessInfo pi, Trx trx)
	{
		//  Preparation
		m_ctx = ctx == null ? Env.getCtx() : ctx;
		m_pi = pi;
		m_trx = trx;
		//***	Trx
		boolean localTrx = m_trx == null;
		if (localTrx)
		{
			m_trx = Trx.get(Trx.createTrxName("SvrProcess"), true);
			m_trx.setDisplayName(getClass().getName()+"_startProcess");
		}
		m_pi.setTransactionName(m_trx.getTrxName());
		m_pi.setProcessUI(processUI);
		//
		ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
		ClassLoader processLoader = getClass().getClassLoader();
		try {
			if (processLoader != contextLoader) {
				Thread.currentThread().setContextClassLoader(processLoader);
			}
			lock();
			
			boolean success = false;
					
			try 
			{			
				m_ctx.put(PROCESS_INFO_CTX_KEY, m_pi);
				if (processUI != null)
					m_ctx.put(PROCESS_UI_CTX_KEY, processUI);
				success = process();			
			}
			finally
			{
				m_ctx.remove(PROCESS_INFO_CTX_KEY);
				m_ctx.remove(PROCESS_UI_CTX_KEY);
				if (localTrx)
				{
					if (success)
					{
						try 
						{
							m_trx.commit(true);
						} catch (Exception e)
						{
							log.log(Level.SEVERE, "Commit failed.", e);
							m_pi.addSummary("Commit Failed.");
							m_pi.setError(true);
						}
					}
					else
						m_trx.rollback();
					m_trx.close();
					m_trx = null;
					m_pi.setTransactionName(null);
					
					unlock();
					
					// outside transaction processing [ teo_sarca, 1646891 ]
					postProcess(!m_pi.isError());

					@SuppressWarnings("unused")
					Event eventPP = sendProcessEvent(IEventTopics.POST_PROCESS);

				}
				else
				{
					m_trx.addTrxEventListener(new TrxEventListener() {
					
						@Override
						public void afterRollback(Trx trx, boolean success) {							
						}
						
						@Override
						public void afterCommit(Trx trx, boolean success) {
						}
						
						@Override
						public void afterClose(Trx trx) {
							unlock();
							
							// outside transaction processing [ teo_sarca, 1646891 ]
							postProcess(!m_pi.isError());
							@SuppressWarnings("unused")
							Event eventPP = sendProcessEvent(IEventTopics.POST_PROCESS);
						}
					});
				}
				
				Thread.currentThread().setContextClassLoader(contextLoader);
			}
		} finally {
			if (processLoader != contextLoader) {
				Thread.currentThread().setContextClassLoader(contextLoader);
			}
		}
		
		return !m_pi.isError();
	}   //  startProcess

	
	/**************************************************************************
	 *  Process
	 *  @return true if success
	 */
	private boolean process()
	{
		String msg = null;
		boolean success = true;
		try
		{
			prepare();

			// event before process
			Event eventBP = sendProcessEvent(IEventTopics.BEFORE_PROCESS);
			@SuppressWarnings("unchecked")
			List<String> errorsBP = (List<String>) eventBP.getProperty(IEventManager.EVENT_ERROR_MESSAGES);
			if (errorsBP != null && !errorsBP.isEmpty()) {
				msg = "@Error@:" + errorsBP.get(0);
			} else {
				msg = doIt();
				if (msg != null && ! msg.startsWith("@Error@")) {
					Event eventAP = sendProcessEvent(IEventTopics.AFTER_PROCESS);
					@SuppressWarnings("unchecked")
					List<String> errorsAP = (List<String>) eventAP.getProperty(IEventManager.EVENT_ERROR_MESSAGES);
					if (errorsAP != null && !errorsAP.isEmpty()) {
						msg = "@Error@:" + errorsAP.get(0);
					}
				}
			}
		}
		catch (Throwable e)
		{
			msg = e.getLocalizedMessage();
			if (msg == null)
				msg = e.toString();
			if (e.getCause() != null)
				log.log(Level.SEVERE, msg, e.getCause());
			else 
				log.log(Level.SEVERE, msg, e);
			success = false;
		//	throw new RuntimeException(e);
		}
		
		//transaction should rollback if there are error in process
		if(msg != null && msg.startsWith("@Error@"))
			success = false;

		if (success) {
			m_trx.addTrxEventListener(new TrxEventListener() {				
				@Override
				public void afterRollback(Trx trx, boolean success) {
				}
				
				@Override
				public void afterCommit(Trx trx, boolean success) {
					if (success)
						flushBufferLog();
				}
				
				@Override
				public void afterClose(Trx trx) {
				}
			});
		}

		//	Parse Variables
		msg = Msg.parseTranslation(m_ctx, msg);
		m_pi.setSummary (msg, !success);
		
		return success;
	}   //  process

	private Event sendProcessEvent(String topic) {
		Event event = EventManager.newEvent(topic,
				new EventProperty(EventManager.EVENT_DATA, m_pi),
				new EventProperty(EventManager.PROCESS_UID_PROPERTY, m_pi.getAD_Process_UU()),
				new EventProperty(EventManager.CLASS_NAME_PROPERTY, m_pi.getClassName()),
				new EventProperty(EventManager.PROCESS_CLASS_NAME_PROPERTY, this.getClass().getName()));
		EventManager.getInstance().sendEvent(event);
		return event;
	}

	/**
	 *  Prepare - e.g., get Parameters.
	 *  <code>
		ProcessInfoParameter[] para = getParameter();
		for (int i = 0; i < para.length; i++)
		{
			String name = para[i].getParameterName();
			if (para[i].getParameter() == null)
				;
			else if (name.equals("A_Asset_Group_ID"))
				p_A_Asset_Group_ID = para[i].getParameterAsInt();
			else if (name.equals("GuaranteeDate"))
				p_GuaranteeDate = (Timestamp)para[i].getParameter();
			else if (name.equals("AttachAsset"))
				p_AttachAsset = "Y".equals(para[i].getParameter());
			else
				log.log(Level.SEVERE, "Unknown Parameter: " + name);
		}
	 *  </code>
	 */
	abstract protected void prepare();

	/**
	 *  Perform process.
	 *  @return Message (variables are parsed)
	 *  @throws Exception if not successful e.g.
	 *  throw new AdempiereUserError ("@FillMandatory@  @C_BankAccount_ID@");
	 */
	abstract protected String doIt() throws Exception;

	/**
	 * Post process actions (outside trx).
	 * Please note that at this point the transaction is committed so
	 * you can't rollback.
	 * This method is useful if you need to do some custom work when 
	 * the process complete the work (e.g. open some windows).
	 *  
	 * @param success true if the process was success
	 * @since 3.1.4
	 */
	protected void postProcess(boolean success) {
	}
	
	/**
	 * 	Commit
	 *  @deprecated suggested to use commitEx instead
	 */
	protected void commit()
	{
		if (m_trx != null)
			m_trx.commit();
	}	//	commit
	
	/**
	 * Commit and throw exception if error
	 * @throws SQLException on commit error
	 */
	protected void commitEx() throws SQLException
	{
		if (m_trx != null)
			m_trx.commit(true);
	}
	
	/**
	 * 	Rollback
	 */
	protected void rollback()
	{
		if (m_trx != null)
			m_trx.rollback();
	}	//	rollback
	
	
	/**************************************************************************
	 * 	Lock Object.
	 * 	Needs to be explicitly called. Unlock is automatic.
	 *	@param po object
	 *	@return true if locked
	 */
	protected boolean lockObject (PO po)
	{
		//	Unlock existing
		if (m_locked || m_lockedObject != null)
			unlockObject();
		//	Nothing to lock			
		if (po == null)
			return false;
		m_lockedObject = po;
		m_locked = m_lockedObject.lock();
		return m_locked;
	}	//	lockObject

	/**
	 * 	Is an object Locked?
	 *	@return true if object locked
	 */
	protected boolean isLocked()
	{
		return m_locked;
	}	//	isLocked

	/**
	 * 	Unlock Object.
	 * 	Is automatically called at the end of process.
	 *	@return true if unlocked or if there was nothing to unlock
	 */
	protected boolean unlockObject()
	{
		boolean success = true;
		if (m_locked || m_lockedObject != null)
		{
			success = m_lockedObject.unlock(null);
		}
		m_locked = false;
		m_lockedObject = null;
		return success;
	}	//	unlock


	/**************************************************************************
	 *  Get Process Info
	 *  @return Process Info
	 */
	public ProcessInfo getProcessInfo()
	{
		return m_pi;
	}   //  getProcessInfo

	/**
	 *  Get Properties
	 *  @return Properties
	 */
	public Properties getCtx()
	{
		return m_ctx;
	}   //  getCtx

	/**
	 *  Get Name/Title
	 *  @return Name
	 */
	protected String getName()
	{
		return m_pi.getTitle();
	}   //  getName

	/**
	 *  Get Process Instance
	 *  @return Process Instance
	 */
	protected int getAD_PInstance_ID()
	{
		return m_pi.getAD_PInstance_ID();
	}   //  getAD_PInstance_ID

	/**
	 *  Get Table_ID
	 *  @return AD_Table_ID
	 */
	protected int getTable_ID()
	{
		return m_pi.getTable_ID();
	}   //  getRecord_ID

	/**
	 *  Get Record_ID
	 *  @return Record_ID
	 */
	protected int getRecord_ID()
	{
		return m_pi.getRecord_ID();
	}   //  getRecord_ID

	/**
	 * Get Record_IDs
	 * 
	 * @return Record_IDs
	 */
	protected List<Integer> getRecord_IDs() 
	{
		return m_pi.getRecord_IDs();
	} // getRecord_IDs

	/**
	 *  Get AD_User_ID
	 *  @return AD_User_ID of Process owner or -1 if not found
	 */
	protected int getAD_User_ID()
	{
		if (m_pi.getAD_User_ID() == null || m_pi.getAD_Client_ID() == null)
		{
			String sql = "SELECT AD_User_ID, AD_Client_ID FROM AD_PInstance WHERE AD_PInstance_ID=?";
			PreparedStatement pstmt = null;
			ResultSet rs = null;
			try
			{
				pstmt = DB.prepareStatement(sql, get_TrxName());
				pstmt.setInt(1, m_pi.getAD_PInstance_ID());
				rs = pstmt.executeQuery();
				if (rs.next())
				{
					m_pi.setAD_User_ID (rs.getInt (1));
					m_pi.setAD_Client_ID (rs.getInt(2));
				}
			}
			catch (SQLException e)
			{
				log.log(Level.SEVERE, sql, e);
			}
			finally {
				DB.close(rs, pstmt);
				rs = null; pstmt = null;
			}
		}
		if (m_pi.getAD_User_ID() == null)
			return -1;
		return m_pi.getAD_User_ID().intValue();
	}   //  getAD_User_ID

	/**
	 *  Get AD_User_ID
	 *  @return AD_User_ID of Process owner
	 */
	protected int getAD_Client_ID()
	{
		if (m_pi.getAD_Client_ID() == null)
		{
			getAD_User_ID();	//	sets also Client
			if (m_pi.getAD_Client_ID() == null)
				return 0;
		}
		return m_pi.getAD_Client_ID().intValue();
	}	//	getAD_Client_ID

	
	/**************************************************************************
	 * 	Get Parameter
	 *	@return parameter
	 */
	protected ProcessInfoParameter[] getParameter()
	{
		ProcessInfoParameter[] retValue = m_pi.getParameter();
		if (retValue == null)
		{
			ProcessInfoUtil.setParameterFromDB(m_pi);
			retValue = m_pi.getParameter();
		}
		return retValue;
	}	//	getParameter


	/**************************************************************************
	 *  Add Log Entry with table name
	 *  
	 */
	public void addLog (int id, Timestamp date, BigDecimal number, String msg, int tableId ,int recordId)
	{
		if (m_pi != null)
			m_pi.addLog(id, date, number, msg,tableId,recordId);
		
		if (log.isLoggable(Level.INFO)) log.info(id + " - " + date + " - " + number + " - " + msg + " - " + tableId + " - " + recordId);
	}	//	addLog

	/**************************************************************************
	 *  Add Log Entry
	 *  @param date date or null
	 *  @param id record id or 0
	 *  @param number number or null
	 *  @param msg message or null
	 */
	public void addLog (int id, Timestamp date, BigDecimal number, String msg)
	{
		if (m_pi != null)
			m_pi.addLog(id, date, number, msg);
		if (log.isLoggable(Level.INFO)) log.info(id + " - " + date + " - " + number + " - " + msg);
	}	//	addLog

	/**
	 * 	Add Log
	 *	@param msg message
	 */
	public void addLog (String msg)
	{
		if (msg != null)
			addLog (0, null, null, msg);
	}	//	addLog

	private void flushBufferLog () {
		if (listEntryLog == null)
			return;

		for (ProcessInfoLog entryLog : listEntryLog) {
			if (m_pi != null)
				m_pi.addLog(entryLog);
			if (log.isLoggable(Level.INFO)) log.info(entryLog.getP_ID() + " - " + entryLog.getP_Date() + " - " + entryLog.getP_Number() + " - " + entryLog.getP_Msg() + " - " + entryLog.getAD_Table_ID() + " - " + entryLog.getRecord_ID());
		}							
	}

	/**************************************************************************
	 * 	Execute function
	 * 	@param className class
	 * 	@param methodName method
	 * 	@param args arguments
	 * 	@return result
	 */
	public Object doIt (String className, String methodName, Object args[])
	{
		try
		{
			Class<?> clazz = Class.forName(className);
			Object object = clazz.getDeclaredConstructor().newInstance();
			Method[] methods = clazz.getMethods();
			for (int i = 0; i < methods.length; i++)
			{
				if (methods[i].getName().equals(methodName))
					return methods[i].invoke(object, args);
			}
		}
		catch (Exception ex)
		{
			log.log(Level.SEVERE, "doIt", ex);
			throw new RuntimeException(ex);
		}
		return null;
	}	//	doIt

	
	/**************************************************************************
	 *  Lock Process Instance
	 */
	private void lock()
	{
		if (log.isLoggable(Level.FINE)) log.fine("AD_PInstance_ID=" + m_pi.getAD_PInstance_ID());
		try 
		{
			DB.executeUpdate("UPDATE AD_PInstance SET IsProcessing='Y' WHERE AD_PInstance_ID=" 
				+ m_pi.getAD_PInstance_ID(), null);		//	outside trx
		} catch (Exception e)
		{
			log.severe("lock() - " + e.getLocalizedMessage());
		}
	}   //  lock

	/**
	 *  Unlock Process Instance.
	 *  Update Process Instance DB and write option return message
	 */
	private void unlock ()
	{
		boolean noContext = Env.getCtx().isEmpty() && Env.getCtx().getProperty(Env.AD_CLIENT_ID) == null;
		try 
		{
			//save logging info even if context is lost
			if (noContext)
				Env.getCtx().put(Env.AD_CLIENT_ID, m_pi.getAD_Client_ID());

			//clear interrupt signal so that we can unlock the ad_pinstance record
			if (Thread.currentThread().isInterrupted())
				Thread.interrupted();
				
			MPInstance mpi = new MPInstance (getCtx(), m_pi.getAD_PInstance_ID(), null);
			if (mpi.get_ID() == 0)
			{
				log.log(Level.SEVERE, "Did not find PInstance " + m_pi.getAD_PInstance_ID());
				return;
			}
			mpi.setIsProcessing(false);
			mpi.setResult(!m_pi.isError());
			mpi.setErrorMsg(m_pi.getSummary());
			mpi.saveEx();
			if (log.isLoggable(Level.FINE)) log.fine(mpi.toString());
			
			ProcessInfoUtil.saveLogToDB(m_pi);
		} 
		catch (Exception e)
		{
			log.severe("unlock() - " + e.getLocalizedMessage());
		}
		finally
		{
			if (noContext)
				Env.getCtx().remove(Env.AD_CLIENT_ID);
		}
	}   //  unlock

	/**
	 * Return the main transaction of the current process.
	 * @return the transaction name
	 */
	public String get_TrxName()
	{
		if (m_trx != null)
			return m_trx.getTrxName();
		return null;
	}	//	get_TrxName

	@Override
	public void setProcessUI(IProcessUI monitor)
	{
		processUI = monitor;
	}
	
	/**
	 * publish status update message
	 * @param message
	 */
	protected void statusUpdate(String message)
	{
		if (processUI != null)
		{
			processUI.statusUpdate(message);
		}
	}
}   //  SvrProcess
