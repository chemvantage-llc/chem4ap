import React from 'react';

interface SplashPageProps {
    message: string;
}

const SplashPage: React.FC<SplashPageProps> = ({ message }) => {
    return (
        <>
            <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh', flexDirection: 'column'}}>
                <img src="/images/chem4ap_atom.png" alt="Logo" style={{ animation: 'rotate 30s linear infinite' }} />
                <p></p>
                <h1>Chem4AP</h1>
                <h2>{message}</h2>
            </div>
            <style>
                {`
                @keyframes rotate {
                    from {
                    transform: rotate(0deg);
                    }
                    to {
                    transform: rotate(360deg);
                    }
                }
                `}
            </style>
        </>
    );
};

export default SplashPage;