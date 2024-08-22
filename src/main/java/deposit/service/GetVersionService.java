package deposit.service;

import deposit.common.DepositInfo;

public class GetVersionService extends BaseService {

	private static GetVersionService instance;

	private GetVersionService() {}

	public static synchronized GetVersionService getInstance() {
		if (instance == null) {
			instance = new GetVersionService();
		}
		return instance;
	}

	public String getVersion() {
		
		return DepositInfo.version;
	}
}
