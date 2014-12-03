package nxt.at;


import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NavigableSet;
import java.util.concurrent.ConcurrentSkipListMap;

import nxt.AT;
import nxt.Account;
import nxt.Nxt;
import nxt.util.Convert;
import nxt.util.Logger;

public abstract class AT_Controller {


	public static int runSteps( AT_Machine_State state )
	{
		state.getMachineState().finished = false;
		state.getMachineState().steps = 0;

		AT_Machine_Processor processor = new AT_Machine_Processor( state );

		int height = Nxt.getBlockchain().getHeight();

		state.setFreeze( false );
		
		while ( state.getMachineState().steps < AT_Constants.getInstance().MAX_STEPS( height ))
		{
			long stepFee = AT_Constants.getInstance().STEP_FEE( height );

			if ( ( state.getG_balance() < stepFee ) )
			{
				System.out.println( "stopped - not enough balance" );
				state.setFreeze( true );
				return 3;
			}

			state.setG_balance( state.getG_balance() - stepFee );
			state.getMachineState().steps++;
			int rc = processor.processOp( false , false );

			if ( rc >= 0 )
			{
				if ( state.getMachineState().stopped )
				{
					System.out.println( "stopped" );
					return 2;
				}
				else if ( state.getMachineState().finished )
				{
					System.out.println( "finished" );
					return 1;
				}
			}
			else
			{
				if ( rc == -1 )
					System.out.println( "error: overflow" );
				else if ( rc==-2 )
					System.out.println( "error: invalid code" );
				else
					System.out.println( "unexpected error" );
				return 0;
			}
		}
		return 5;
	}

	public static void resetMachine( AT_Machine_State state ) {
		state.getMachineState( ).reset( );
		listCode( state , true , true );
	}

	public static void listCode( AT_Machine_State state , boolean disassembly , boolean determine_jumps ) {

		AT_Machine_Processor machineProcessor = new AT_Machine_Processor( state );

		int opc = state.getMachineState().pc;
		int osteps = state.getMachineState().steps;

		state.getAp_code().order( ByteOrder.LITTLE_ENDIAN );
		state.getAp_data().order( ByteOrder.LITTLE_ENDIAN );

		state.getMachineState( ).pc = 0;
		state.getMachineState( ).opc = opc;

		while ( true )
		{
			int rc= machineProcessor.processOp( disassembly , determine_jumps );
			if ( rc<=0 ) break;

			state.getMachineState().pc += rc;
		}

		state.getMachineState().steps = osteps;
		state.getMachineState().pc = opc;
	}

	public static int checkCreationBytes( byte[] creation , int height ) throws AT_Exception{
		int totalPages = 0;
		try 
		{
			ByteBuffer b = ByteBuffer.allocate( creation.length );
			b.order( ByteOrder.LITTLE_ENDIAN );

			b.put(  creation );
			b.clear();

			AT_Constants instance = AT_Constants.getInstance();

			short version = b.getShort();
			if ( version != instance.AT_VERSION( height ) )
			{
				throw new AT_Exception( AT_Error.INCORRECT_VERSION.getDescription() );
			}

			short reserved = b.getShort(); //future: reserved for future needs

			short codePages = b.getShort();
			if ( codePages > instance.MAX_MACHINE_CODE_PAGES( height ) || codePages < 1)
			{
				throw new AT_Exception( AT_Error.INCORRECT_CODE_PAGES.getDescription() );
			}

			short dataPages = b.getShort();
			if ( dataPages > instance.MAX_MACHINE_DATA_PAGES( height ) || dataPages < 0)
			{
				throw new AT_Exception( AT_Error.INCORRECT_DATA_PAGES.getDescription() );
			}

			short callStackPages = b.getShort();
			if ( callStackPages > instance.MAX_MACHINE_CALL_STACK_PAGES( height ) || callStackPages < 0)
			{
				throw new AT_Exception( AT_Error.INCORRECT_CALL_PAGES.getDescription() );
			}

			short userStackPages = b.getShort();
			if ( userStackPages > instance.MAX_MACHINE_USER_STACK_PAGES( height ) || userStackPages < 0)
			{
				throw new AT_Exception( AT_Error.INCORRECT_USER_PAGES.getDescription() );
			}
			System.out.println("codePages: " + codePages );
			int codeLen;
			if ( codePages * 256 < 257 )
			{
				codeLen = b.get();
			}
			else if ( codePages * 256 < Short.MAX_VALUE + 1 )
			{
				codeLen = b.getShort();
			}
			else if ( codePages * 256 <= Integer.MAX_VALUE )
			{
				codeLen = b.getInt();
			}
			else
			{
				throw new AT_Exception( AT_Error.INCORRECT_CODE_LENGTH.getDescription() );
			}
			if ( codeLen < 1 )
			{
				throw new AT_Exception( AT_Error.INCORRECT_CODE_LENGTH.getDescription() );
			}
			byte[] code = new byte[ codeLen ];
			b.get( code , 0 , codeLen );


			int dataLen;
			if ( dataPages * 256 < 257 )
			{
				dataLen = b.get();
			}
			else if ( dataPages * 256 < Short.MAX_VALUE + 1 )
			{
				dataLen = b.getShort();
			}
			else if ( dataPages * 256 <= Integer.MAX_VALUE )
			{
				dataLen = b.getInt();
			}
			else
			{
				throw new AT_Exception( AT_Error.INCORRECT_CODE_LENGTH.getDescription() );
			}
			if( dataLen < 0 )
			{
				throw new AT_Exception( AT_Error.INCORRECT_DATA_LENGTH.getDescription() );
			}
			byte[] data = new byte[ dataLen ];
			b.get( data , 0 , dataLen );

			totalPages = codePages + dataPages + userStackPages + callStackPages;
			/*if ( ( codePages + dataPages + userStackPages + callStackPages ) * instance.COST_PER_PAGE( height ) < txFeeAmount )
			{
				return AT_Error.INCORRECT_CREATION_FEE.getCode();
			}*/

			if ( b.position() != b.capacity() )
			{
				throw new AT_Exception( AT_Error.INCORRECT_CREATION_TX.getDescription() );
			}

			//TODO note: run code in demo mode for checking if is valid

		} catch ( BufferUnderflowException e ) 
		{
			throw new AT_Exception( AT_Error.INCORRECT_CREATION_TX.getDescription() );
		}
		return totalPages;
	}

	public static AT_Block getCurrentBlockATs( int freePayload , int blockHeight ){

		List< Long > orderedATs = AT.getOrderedATs();
		Iterator< Long > keys = orderedATs.iterator();

		List< AT > processedATs = new ArrayList< >();

		int costOfOneAT = getCostOfOneAT();
		int payload = 0;
		long totalSteps = 0;
		while ( payload <= freePayload - costOfOneAT && keys.hasNext() )
		{
				Long id = keys.next();
				AT at = AT.getAT( id );

				long atAccountBalance = getATAccountBalance( id );
				long atStateBalance = at.getG_balance();

				if ( at.freezeOnSameBalance() && atAccountBalance == atStateBalance )
				{
					continue;
				}



				if ( atAccountBalance >= AT_Constants.getInstance().STEP_FEE( blockHeight ) )
				{
					try
					{
						at.setG_balance( atAccountBalance );
						at.clearTransactions();
						at.setWaitForNumberOfBlocks( at.getSleepBetween() );
						listCode(at, true, true);
						runSteps ( at );

						totalSteps += at.getMachineState().steps;
						AT.addPendingFee(id, at.getMachineState().steps * AT_Constants.getInstance().STEP_FEE( blockHeight ));

						payload += costOfOneAT;

						at.setP_balance( at.getG_balance() );
						processedATs.add( at );

						at.saveState();
					}
					catch ( Exception e )
					{
						
					}
				}
		}

		long totalAmount = 0;
		for ( AT at : processedATs )
		{
			totalAmount = makeTransactions( at );
		}

		byte[] bytesForBlock = null;

		try
		{
			bytesForBlock = getBlockATBytes( processedATs , payload );
		}
		catch ( NoSuchAlgorithmException e )
		{
			//should not reach ever here
			e.printStackTrace();
		}

		AT_Block atBlock = new AT_Block( totalSteps * AT_Constants.getInstance().STEP_FEE( blockHeight ) , totalAmount , bytesForBlock );

		return atBlock;
	}

	public static AT_Block validateATs( byte[] blockATs , int blockHeight ) throws NoSuchAlgorithmException, AT_Exception {

		if(blockATs == null)
		{
			return new AT_Block(0, 0, null, true);
		}
		
		LinkedHashMap< byte[] , byte[] > ats = getATsFromBlock( blockATs );

		List< AT > processedATs = new ArrayList< >();

		boolean validated = true;
		long totalSteps = 0;
		MessageDigest digest = MessageDigest.getInstance( "MD5" );
		byte[] md5 = null;
		for ( byte[] atId : ats.keySet() )
		{
			AT at = AT.getAT( atId );

			try
			{
				at.clearTransactions();
				at.setWaitForNumberOfBlocks( at.getSleepBetween() );

				long atAccountBalance = getATAccountBalance( AT_API_Helper.getLong( atId ) );
				if(atAccountBalance < AT_Constants.getInstance().STEP_FEE( blockHeight ))
				{
					throw new AT_Exception( "AT has insufficient balance to run" );
				}
				
				if ( at.freezeOnSameBalance() && atAccountBalance == at.getG_balance() )
				{
					throw new AT_Exception( "AT should be frozen due to unchanged balance" );
				}

				at.setG_balance( atAccountBalance );
				
				listCode(at, true, true);

				runSteps( at );

				totalSteps += at.getMachineState().steps;
				AT.addPendingFee(atId, at.getMachineState().steps * AT_Constants.getInstance().STEP_FEE( blockHeight ));

				at.setP_balance( at.getG_balance() );
				processedATs.add( at );

				md5 = digest.digest( at.getBytes() );
				if ( !Arrays.equals( md5 , ats.get( atId ) ) )
				{
					throw new AT_Exception( "Calculated md5 and recieved md5 are not matching" );
				}
			}
			catch ( Exception e )
			{
				throw new AT_Exception( "ATs error. Block rejected" );
			}
		}

		long totalAmount = 0;
		for ( AT at : processedATs )
		{
			at.saveState();
			totalAmount = makeTransactions( at );
		}
		AT_Block atBlock = new AT_Block( totalSteps * AT_Constants.getInstance().STEP_FEE( blockHeight ) , totalAmount , new byte[ 1 ] , validated );

		return atBlock;
	}

	public static LinkedHashMap< byte[] , byte[] > getATsFromBlock( byte[] blockATs ) throws AT_Exception
	{
		if ( blockATs.length > 0 )
		{
			if ( blockATs.length % (getCostOfOneAT() ) != 0 )
			{
				throw new AT_Exception("blockATs must be a multiple of cost of one AT ( " + getCostOfOneAT() +" )" );
			}
		}

		ByteBuffer b = ByteBuffer.wrap( blockATs );
		b.order( ByteOrder.LITTLE_ENDIAN );

		byte[] temp = new byte[ AT_Constants.AT_ID_SIZE ];
		byte[] md5 = new byte[ 16 ];

		LinkedHashMap< byte[] , byte[] > ats = new LinkedHashMap<>();

		while ( b.position() < b.capacity() )
		{
			b.get( temp , 0 , temp.length );
			b.get( md5 , 0 , md5.length );
			if(ats.containsKey(temp)) {
				throw new AT_Exception("AT included in block multiple times");
			}
			ats.put( temp , md5 ); 
		}

		if ( b.position() != b.capacity() )
		{
			throw new AT_Exception("bytebuffer not matching");
		}

		return ats;
	}

	private static byte[] getBlockATBytes(List<AT> processedATs , int payload ) throws NoSuchAlgorithmException {

		if(payload <= 0)
		{
			return null;
		}
		
		ByteBuffer b = ByteBuffer.allocate( payload );
		b.order( ByteOrder.LITTLE_ENDIAN );

		MessageDigest digest = MessageDigest.getInstance( "MD5" );
		for ( AT at : processedATs )
		{
			b.put( at.getId() );
			digest.update( at.getBytes() );
			b.put( digest.digest() );
		}

		return b.array();
	}

	private static int getCostOfOneAT() {
		return AT_Constants.AT_ID_SIZE + 16;
	}

	//platform based implementations
	//platform based 
	private static long makeTransactions( AT at ) {
		long totalAmount = 0;
		for (AT_Transaction tx : at.getTransactions() )
		{
			totalAmount += tx.getAmount();
			AT.addPendingTransaction(tx);
			Logger.logInfoMessage("Transaction to " + Convert.toUnsignedLong(AT_API_Helper.getLong(tx.getRecipientId())) + " amount " + tx.getAmount() );

		}
		return totalAmount;
	}


	//platform based


	//platform based
	private static long getATAccountBalance( Long id ) {
		//Long accountId = AT_API_Helper.getLong( id );
		Account atAccount = Account.getAccount( id );

		if ( atAccount != null )
		{
			return atAccount.getBalanceNQT();
		}

		return 0;

	}



}
