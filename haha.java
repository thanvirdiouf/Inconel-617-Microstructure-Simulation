public class haha
{
	 ArrayList<Double> strainRate = new ArrayList<Double>();
	 for (double k = -2.05; k <= 0.95; k += 0.05)					//Possible to adjust strainrate interval
            strainRate.add(Math.pow(10,k));
	for(Double sr:strainRate)
	{
		System.out.print(sr);
	}
}