package eu.unicore.ucc;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import org.junit.Test;

import eu.unicore.ucc.authn.KeystoreAuthN;
import eu.unicore.ucc.helpers.EndProcessingException;

public class TestUCC {

	@Test
	public void test_PrintUsage(){
		UCC.printUsage(false);
		System.err.println("\n");
		UCC.printAuthNUsage();
		UCC.printAuthNUsage(KeystoreAuthN.X509);
	}

	@Test
	public void test_ShowHelp(){
		UCC.unitTesting = true;
		UCC.mute=true;
		String[]args=new String []{"-h"};
		try{
			for(Command cmd: UCC.getAllCommands()){
				try{
					cmd.init(args);
				}catch(EndProcessingException epe){}
			};
		}catch(Exception ex){
			ex.printStackTrace();
			fail();
		}
		args=new String []{"help-auth"};
		UCC.main(args);
		UCC.mute=false;
	}
	
	@Test
	public void test_LoadAuthNMethods(){
		assertNotNull(UCC.getAuthNMethod("X509"));
		assertNotNull(UCC.getAuthNMethod("x509"));
	}
	
}
